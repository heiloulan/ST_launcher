import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const fsPromises = fs.promises;
const COPY_FALLBACK_ERRORS = new Set(['EXDEV', 'EPERM', 'EACCES', 'EMLINK', 'ENOTSUP']);
const ARCHIVE_SPACE_RESERVE = 64 * 1024 * 1024;

function isMissing(error) {
    return error?.code === 'ENOENT';
}

async function snapshotDirectory(source, destination, state, isRoot = false) {
    let entries;
    try {
        entries = await fsPromises.readdir(source, { withFileTypes: true });
    } catch (error) {
        if (!isRoot && isMissing(error)) {
            state.skipped.push(source);
            return;
        }
        throw error;
    }

    await fsPromises.mkdir(destination, { recursive: true });

    for (const entry of entries) {
        if (isRoot && entry.name === 'temp_uploads') {
            continue;
        }

        const sourcePath = path.join(source, entry.name);
        const destinationPath = path.join(destination, entry.name);

        try {
            if (entry.isDirectory()) {
                await snapshotDirectory(sourcePath, destinationPath, state);
                continue;
            }

            if (entry.isSymbolicLink()) {
                const target = await fsPromises.readlink(sourcePath);
                await fsPromises.symlink(target, destinationPath);
                state.fileCount++;
                continue;
            }

            if (!entry.isFile()) {
                state.skipped.push(sourcePath);
                continue;
            }

            const stat = await fsPromises.stat(sourcePath);
            try {
                await fsPromises.link(sourcePath, destinationPath);
            } catch (error) {
                if (isMissing(error)) {
                    state.skipped.push(sourcePath);
                    continue;
                }
                if (!COPY_FALLBACK_ERRORS.has(error?.code)) {
                    throw error;
                }
                await fsPromises.copyFile(sourcePath, destinationPath);
            }

            state.fileCount++;
            state.totalBytes += stat.size;
        } catch (error) {
            if (isMissing(error)) {
                // A live file may be atomically replaced or removed while the
                // directory is being snapshotted. Omitting that vanished inode
                // is safe; files already hard-linked into the snapshot remain.
                state.skipped.push(sourcePath);
                continue;
            }
            throw error;
        }
    }
}

export async function createDataSnapshot(sourceDataDirectory, snapshotDataDirectory) {
    const state = {
        fileCount: 0,
        totalBytes: 0,
        skipped: [],
    };

    await snapshotDirectory(sourceDataDirectory, snapshotDataDirectory, state, true);
    return state;
}

async function assertArchiveSpace(directory, sourceBytes) {
    if (typeof fsPromises.statfs !== 'function') {
        return;
    }

    const stats = await fsPromises.statfs(directory);
    const availableBytes = Number(stats.bavail) * Number(stats.bsize);
    const requiredBytes = sourceBytes + ARCHIVE_SPACE_RESERVE;

    if (availableBytes < requiredBytes) {
        const error = new Error(
            `Insufficient storage for export. Required about ${requiredBytes} bytes, available ${availableBytes} bytes.`,
        );
        error.code = 'INSUFFICIENT_STORAGE';
        throw error;
    }
}

function runTar(args) {
    return new Promise((resolve, reject) => {
        const child = spawn('tar', args);
        let stderr = '';

        child.stderr.setEncoding('utf8');
        child.stderr.on('data', chunk => {
            stderr = (stderr + chunk).slice(-64 * 1024);
        });
        child.on('error', reject);
        child.on('close', code => {
            if (code === 0) {
                resolve();
                return;
            }

            const error = new Error(stderr.trim() || `tar exited with code ${code}`);
            error.code = 'TAR_FAILED';
            error.exitCode = code;
            reject(error);
        });
    });
}

export async function prepareDataArchive(sourceDataDirectory, workDirectory, id) {
    if (!/^[a-zA-Z0-9-]+$/.test(id)) {
        throw new Error('Invalid export identifier');
    }

    const snapshotRoot = path.join(workDirectory, `${id}.snapshot`);
    const snapshotDataDirectory = path.join(snapshotRoot, 'data');
    const partialArchivePath = path.join(workDirectory, `${id}.tar.gz.part`);
    const archivePath = path.join(workDirectory, `${id}.tar.gz`);

    await fsPromises.mkdir(workDirectory, { recursive: true });
    await fsPromises.rm(snapshotRoot, { recursive: true, force: true });
    await fsPromises.rm(partialArchivePath, { force: true });
    await fsPromises.rm(archivePath, { force: true });

    try {
        const snapshot = await createDataSnapshot(sourceDataDirectory, snapshotDataDirectory);
        await assertArchiveSpace(workDirectory, snapshot.totalBytes);
        await runTar(['-czf', partialArchivePath, '-C', snapshotRoot, 'data']);

        const archiveStat = await fsPromises.stat(partialArchivePath);
        if (archiveStat.size === 0) {
            throw new Error('tar produced an empty archive');
        }

        await fsPromises.rename(partialArchivePath, archivePath);
        return {
            archivePath,
            archiveBytes: archiveStat.size,
            ...snapshot,
        };
    } catch (error) {
        await fsPromises.rm(partialArchivePath, { force: true }).catch(() => {});
        await fsPromises.rm(archivePath, { force: true }).catch(() => {});
        throw error;
    } finally {
        await fsPromises.rm(snapshotRoot, { recursive: true, force: true }).catch(() => {});
    }
}
