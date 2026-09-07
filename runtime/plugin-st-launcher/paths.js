import path from 'node:path';
import { LauncherError } from './errors.js';

export const MAXIMUM_ENTRIES = 200_000;
export const MAXIMUM_PATH_LENGTH = 512;
export const MINIMUM_FREE_BYTES = 64 * 1024 * 1024;

export function requireOperationId(value) {
    if (!/^[0-9a-fA-F-]{36}$/.test(value)) {
        throw new LauncherError('INVALID_OPERATION_ID', 'Invalid operation identifier', 400);
    }
    return value;
}

export function requireSafeBackupPath(value) {
    if (!value || value.length > MAXIMUM_PATH_LENGTH || value.includes('\\') || value.startsWith('/')) {
        throw new LauncherError('UNSAFE_ARCHIVE', `Unsafe backup path: ${value}`, 400);
    }
    if (/^[A-Za-z]:/.test(value)) {
        throw new LauncherError('UNSAFE_ARCHIVE', `Unsafe backup path: ${value}`, 400);
    }
    const normalized = path.posix.normalize(value).replace(/\/+$/, '');
    if (normalized !== value.replace(/\/+$/, '')
        || normalized === '..'
        || normalized.startsWith('../')
        || !(['data', 'global-extensions'].some(root => normalized === root || normalized.startsWith(`${root}/`))
            || normalized === 'config' || normalized === 'config/config.yaml'
            || normalized === 'rescue-report.json')) {
        throw new LauncherError('UNSAFE_ARCHIVE', `Backup entry is outside supported backup roots: ${value}`, 400);
    }
    return normalized;
}

export async function storageBudget(fsPromises, directory) {
    if (typeof fsPromises.statfs !== 'function') {
        return Number.MAX_SAFE_INTEGER;
    }
    const stats = await fsPromises.statfs(directory);
    const blockSize = Number(stats.bsize);
    const totalBytes = Number(stats.blocks) * blockSize;
    const availableBytes = Number(stats.bavail) * blockSize;
    const reserveBytes = Math.max(MINIMUM_FREE_BYTES, Math.min(512 * 1024 * 1024, Math.floor(totalBytes / 100)));
    return Math.max(0, availableBytes - reserveBytes);
}
