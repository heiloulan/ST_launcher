import { createWriteStream, promises as fs } from 'node:fs';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import zlib from 'node:zlib';
import tar from 'tar-stream';
import { LauncherError } from './errors.js';
import {
    MAXIMUM_ENTRIES,
    requireSafeBackupPath,
    storageBudget,
} from './paths.js';

const ALLOWED_TYPES = new Set(['file', 'directory']);

export async function stageImport(request, operationRoot, id) {
    const candidateRoot = path.join(operationRoot, 'candidate');
    const candidateData = path.join(candidateRoot, 'data');
    await fs.mkdir(candidateRoot, { recursive: true });
    const availableBudget = await storageBudget(fs, operationRoot);
    if (availableBudget <= 0) {
        throw new LauncherError(
            'INSUFFICIENT_STORAGE',
            'Not enough free storage to import this backup',
            507,
            true,
        );
    }

    const extract = tar.extract();
    const gunzip = zlib.createGunzip();
    const seen = new Set();
    let entryCount = 0;
    let extractedBytes = 0;

    extract.on('entry', (header, stream, next) => {
        (async () => {
            entryCount++;
            if (entryCount > MAXIMUM_ENTRIES) {
                throw new LauncherError('ARCHIVE_LIMIT', 'Backup has too many entries', 400);
            }
            if (!ALLOWED_TYPES.has(header.type)) {
                throw new LauncherError(
                    'UNSAFE_ARCHIVE',
                    `Backup contains unsupported entry type: ${header.type}`,
                    400,
                );
            }

            const normalized = requireSafeBackupPath(header.name);
            if (seen.has(normalized)) {
                throw new LauncherError(
                    'UNSAFE_ARCHIVE',
                    `Backup contains a duplicate path: ${normalized}`,
                    400,
                );
            }
            seen.add(normalized);
            if ((['data', 'config', 'global-extensions'].includes(normalized) && header.type !== 'directory')
                || (['config/config.yaml', 'rescue-report.json'].includes(normalized) && header.type !== 'file')) {
                throw new LauncherError('UNSAFE_ARCHIVE', 'Backup entry has an invalid type', 400);
            }
            const target = path.resolve(candidateRoot, normalized);
            if (target !== path.resolve(candidateRoot)
                && !target.startsWith(`${path.resolve(candidateRoot)}${path.sep}`)) {
                throw new LauncherError('UNSAFE_ARCHIVE', 'Backup path escaped staging', 400);
            }

            if (header.type === 'directory') {
                await fs.mkdir(target, { recursive: true, mode: 0o700 });
                stream.resume();
                await new Promise((resolve, reject) => {
                    stream.once('end', resolve);
                    stream.once('error', reject);
                });
            } else {
                const declaredSize = Number(header.size);
                if (!Number.isSafeInteger(declaredSize)
                    || declaredSize < 0
                    || extractedBytes + declaredSize > availableBudget) {
                    throw new LauncherError(
                        'INSUFFICIENT_STORAGE',
                        'Backup exceeds the safe extraction budget',
                        507,
                        true,
                    );
                }
                extractedBytes += declaredSize;
                await fs.mkdir(path.dirname(target), { recursive: true, mode: 0o700 });
                await pipeline(
                    stream,
                    createWriteStream(target, { flags: 'wx', mode: 0o600 }),
                );
                const stat = await fs.stat(target);
                if (stat.size !== declaredSize) {
                    throw new LauncherError(
                        'CORRUPT_ARCHIVE',
                        `Backup entry size mismatch: ${normalized}`,
                        400,
                    );
                }
            }
            next();
        })().catch(error => {
            stream.resume();
            extract.destroy(error);
        });
    });

    try {
        await pipeline(request, gunzip, extract);
        if (!await fs.stat(candidateData).then(stat => stat.isDirectory()).catch(() => false)) {
            throw new LauncherError(
                'INVALID_BACKUP',
                'Backup does not contain a data/ directory',
                400,
            );
        }
        const metadata = {
            id,
            kind: 'import',
            status: 'ready',
            entryCount,
            extractedBytes,
            createdAt: Date.now(),
        };
        const partial = path.join(operationRoot, 'operation.json.part');
        await fs.writeFile(partial, JSON.stringify(metadata), { flag: 'wx', mode: 0o600 });
        await fs.rename(partial, path.join(operationRoot, 'operation.json'));
        return metadata;
    } catch (error) {
        await fs.rm(operationRoot, { recursive: true, force: true }).catch(() => {});
        if (error?.code === 'Z_DATA_ERROR' || error?.code === 'TAR_BAD_ARCHIVE') {
            throw new LauncherError('CORRUPT_ARCHIVE', 'Backup archive is corrupt', 400);
        }
        throw error;
    }
}
