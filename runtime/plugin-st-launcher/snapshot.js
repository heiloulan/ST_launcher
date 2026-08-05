import { constants as fsConstants, promises as fs, createReadStream, createWriteStream } from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import zlib from 'node:zlib';
import tar from 'tar-stream';
import { LauncherError } from './errors.js';
import { storageBudget } from './paths.js';

const TRANSIENT_ROOT_ENTRIES = new Set(['_webpack', 'temp_uploads']);
const MAXIMUM_COPY_ATTEMPTS = 3;

function throwIfAborted(signal) {
    if (signal?.aborted) {
        throw new LauncherError('OPERATION_CANCELLED', 'Operation was cancelled', 409);
    }
}

async function listSourceEntries(root, signal) {
    const files = [];
    const directories = [];
    async function visit(directory, relative = '') {
        throwIfAborted(signal);
        const entries = await fs.readdir(directory, { withFileTypes: true });
        entries.sort((left, right) => left.name.localeCompare(right.name));
        for (const entry of entries) {
            if (!relative && TRANSIENT_ROOT_ENTRIES.has(entry.name)) continue;
            const source = path.join(directory, entry.name);
            const childRelative = path.posix.join(relative, entry.name);
            if (entry.isDirectory()) {
                directories.push(childRelative);
                await visit(source, childRelative);
            } else if (entry.isFile()) {
                const stat = await fs.stat(source, { bigint: true });
                files.push({ source, relative: childRelative, size: Number(stat.size) });
            } else {
                throw new LauncherError(
                    'UNSAFE_DATA',
                    `User data contains an unsupported entry: ${childRelative}`,
                    409,
                );
            }
        }
    }
    await visit(root);
    return { files, directories };
}

async function sha256File(file) {
    const digest = crypto.createHash('sha256');
    for await (const chunk of createReadStream(file)) {
        digest.update(chunk);
    }
    return digest.digest('hex');
}

async function copyStableFile(source, destination, signal) {
    await fs.mkdir(path.dirname(destination), { recursive: true });
    for (let attempt = 1; attempt <= MAXIMUM_COPY_ATTEMPTS; attempt++) {
        throwIfAborted(signal);
        const before = await fs.stat(source, { bigint: true });
        const partial = `${destination}.part`;
        await fs.rm(partial, { force: true });
        try {
            await fs.copyFile(source, partial, fsConstants.COPYFILE_FICLONE);
            const [after, copied] = await Promise.all([
                fs.stat(source, { bigint: true }),
                fs.stat(partial, { bigint: true }),
            ]);
            const [sourceDigest, copiedDigest] = await Promise.all([
                sha256File(source),
                sha256File(partial),
            ]);
            const final = await fs.stat(source, { bigint: true });
            if (before.size === after.size
                && before.mtimeNs === after.mtimeNs
                && after.size === final.size
                && after.mtimeNs === final.mtimeNs
                && copied.size === before.size
                && sourceDigest === copiedDigest) {
                await fs.rename(partial, destination);
                return Number(copied.size);
            }
        } catch (error) {
            if (error?.code === 'ENOENT' && attempt < MAXIMUM_COPY_ATTEMPTS) {
                continue;
            }
            throw error;
        } finally {
            await fs.rm(partial, { force: true }).catch(() => {});
        }
    }
    throw new LauncherError(
        'SOURCE_CHANGED',
        `A data file kept changing while it was copied: ${source}`,
        409,
        true,
    );
}

export async function createStableSnapshot(sourceRoot, snapshotRoot, signal) {
    const { files, directories } = await listSourceEntries(sourceRoot, signal);
    const sourceBytes = files.reduce((total, file) => total + file.size, 0);
    await fs.mkdir(snapshotRoot, { recursive: true, mode: 0o700 });
    const budget = await storageBudget(fs, snapshotRoot);
    if (sourceBytes * 2 > budget) {
        throw new LauncherError(
            'INSUFFICIENT_STORAGE',
            'Not enough free storage to create a verified backup',
            507,
            true,
        );
    }
    let copiedBytes = 0;
    for (const directory of directories) {
        await fs.mkdir(path.join(snapshotRoot, directory), { recursive: true, mode: 0o700 });
    }
    for (const file of files) {
        copiedBytes += await copyStableFile(
            file.source,
            path.join(snapshotRoot, file.relative),
            signal,
        );
    }
    return { files, directories, copiedBytes };
}

export async function createTarGzip(snapshotData, archivePath, signal) {
    const { files, directories } = await listSourceEntries(snapshotData, signal);
    const pack = tar.pack();
    const output = createWriteStream(`${archivePath}.part`, { flags: 'wx' });
    const archivePipeline = pipeline(
        pack,
        zlib.createGzip({ level: zlib.constants.Z_BEST_SPEED }),
        output,
    );

    try {
        await new Promise((resolve, reject) => {
            pack.entry({ name: 'data/', type: 'directory', mode: 0o700 }, error => {
                if (error) reject(error); else resolve();
            });
        });
        for (const directory of directories) {
            throwIfAborted(signal);
            await new Promise((resolve, reject) => {
                pack.entry({
                    name: `${path.posix.join('data', directory)}/`,
                    type: 'directory',
                    mode: 0o700,
                }, error => {
                    if (error) reject(error); else resolve();
                });
            });
        }
        for (const file of files) {
            throwIfAborted(signal);
            const entry = pack.entry({
                name: path.posix.join('data', file.relative),
                type: 'file',
                size: file.size,
                mode: 0o600,
            });
            await pipeline(createReadStream(file.source), entry);
        }
        pack.finalize();
        await archivePipeline;
        await fs.rename(`${archivePath}.part`, archivePath);
        const stat = await fs.stat(archivePath);
        if (stat.size <= 0) {
            throw new LauncherError('EMPTY_ARCHIVE', 'Backup archive is empty');
        }
        return {
            archivePath,
            archiveBytes: stat.size,
            fileCount: files.length,
        };
    } catch (error) {
        pack.destroy(error);
        await fs.rm(`${archivePath}.part`, { force: true }).catch(() => {});
        await fs.rm(archivePath, { force: true }).catch(() => {});
        throw error;
    }
}

export async function prepareExport(dataRoot, operationRoot, signal) {
    const snapshotData = path.join(operationRoot, 'snapshot', 'data');
    const archivePath = path.join(operationRoot, 'export.tar.gz');
    await fs.mkdir(operationRoot, { recursive: true });
    try {
        await createStableSnapshot(dataRoot, snapshotData, signal);
        return await createTarGzip(snapshotData, archivePath, signal);
    } finally {
        await fs.rm(path.join(operationRoot, 'snapshot'), {
            recursive: true,
            force: true,
        }).catch(() => {});
    }
}
