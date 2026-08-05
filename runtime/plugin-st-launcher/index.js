import crypto from 'node:crypto';
import { createReadStream, promises as fs } from 'node:fs';
import path from 'node:path';
import { LauncherError, sendError } from './errors.js';
import { stageImport } from './importer.js';
import { requireOperationId } from './paths.js';
import { prepareExport } from './snapshot.js';

const OPERATION_TTL_MS = 15 * 60 * 1000;
const operations = new Map();
let operationsRoot;
let dataRoot;
let activeOperation = null;

export const info = {
    id: 'st-launcher',
    name: 'ST Launcher integration',
    description: 'Health checks and transactional Android backup operations',
};

export async function init(router) {
    operationsRoot = process.env.ST_LAUNCHER_OPERATIONS_ROOT;
    dataRoot = globalThis.DATA_ROOT;
    if (!operationsRoot || !path.isAbsolute(operationsRoot)) {
        throw new Error('ST_LAUNCHER_OPERATIONS_ROOT must be an absolute path');
    }
    if (!dataRoot || !path.isAbsolute(dataRoot)) {
        throw new Error('SillyTavern data root must be absolute');
    }
    await fs.mkdir(operationsRoot, { recursive: true, mode: 0o700 });
    await cleanupExpiredOperations();

    router.get('/health', (request, response) => {
        const instanceId = process.env.ST_LAUNCHER_INSTANCE_ID || '';
        if (!instanceId || request.get('X-ST-Launcher-Instance') !== instanceId) {
            return sendError(
                response,
                new LauncherError('INVALID_INSTANCE', 'Runtime instance mismatch', 403),
            );
        }
        return response.json({
            ready: true,
            instanceId,
            nodeVersion: process.version,
        });
    });

    router.post('/exports', (request, response) => {
        try {
            assertOperationAvailable();
            const operation = createOperation('export', ownerFor(request));
            activeOperation = operation;
            response.status(202).json(publicOperation(operation));
            runExport(operation).catch(error => {
                operation.status = 'failed';
                operation.error = normalizeError(error);
                console.error('[ST Launcher] Export failed:', error);
            }).finally(() => {
                if (activeOperation === operation && operation.status === 'failed') {
                    activeOperation = null;
                }
                scheduleExpiry(operation);
            });
        } catch (error) {
            sendError(response, error);
        }
    });

    router.post('/imports', async (request, response) => {
        let operation;
        try {
            assertOperationAvailable();
            operation = createOperation('import', ownerFor(request));
            operation.status = 'uploading';
            activeOperation = operation;
            const metadata = await stageImport(
                request,
                operation.directory,
                operation.id,
            );
            operation.status = 'ready';
            operation.entryCount = metadata.entryCount;
            operation.extractedBytes = metadata.extractedBytes;
            response.status(202).json(publicOperation(operation));
            scheduleExpiry(operation);
        } catch (error) {
            if (operation) {
                operations.delete(operation.id);
                await fs.rm(operation.directory, { recursive: true, force: true }).catch(() => {});
            }
            sendError(response, error);
        } finally {
            // A validated import remains the single active transaction until
            // Android stops Node to commit it, the user cancels it, or it
            // expires. This prevents multiple prepared imports from racing.
            if (activeOperation === operation && operation?.status !== 'ready') {
                activeOperation = null;
            }
        }
    });

    router.get('/operations/:id', (request, response) => {
        try {
            const operation = findOwnedOperation(request);
            response.json(publicOperation(operation));
        } catch (error) {
            sendError(response, error);
        }
    });

    router.delete('/operations/:id', async (request, response) => {
        try {
            const operation = findOwnedOperation(request);
            operation.controller.abort();
            operation.status = 'cancelled';
            if (activeOperation === operation) activeOperation = null;
            await removeOperation(operation);
            response.sendStatus(204);
        } catch (error) {
            sendError(response, error);
        }
    });

    router.get('/exports/:id/archive', (request, response) => {
        try {
            const operation = findOwnedOperation(request, 'export');
            if (operation.status !== 'ready' || !operation.archivePath) {
                throw new LauncherError(
                    'EXPORT_NOT_READY',
                    'Export is not ready',
                    409,
                    true,
                );
            }
            response.setHeader('Content-Type', 'application/gzip');
            response.setHeader(
                'Content-Disposition',
                `attachment; filename="${operation.filename}"`,
            );
            response.setHeader('Content-Length', String(operation.archiveBytes));
            response.setHeader('Cache-Control', 'no-store');
            const stream = createReadStream(operation.archivePath);
            let finished = false;
            stream.on('error', error => sendError(response, error));
            response.on('finish', () => {
                finished = true;
                removeOperation(operation).catch(console.error);
            });
            response.on('close', () => {
                if (!finished) operation.status = 'ready';
            });
            operation.status = 'downloading';
            stream.pipe(response);
        } catch (error) {
            sendError(response, error);
        }
    });
}

export async function exit() {
    activeOperation?.controller.abort();
}

function assertOperationAvailable() {
    if (activeOperation) {
        throw new LauncherError(
            'OPERATION_BUSY',
            'Another backup operation is already running',
            409,
            true,
        );
    }
}

function createOperation(kind, owner) {
    const id = crypto.randomUUID();
    const operation = {
        id,
        kind,
        owner,
        status: 'queued',
        createdAt: Date.now(),
        directory: path.join(operationsRoot, id),
        controller: new AbortController(),
    };
    operations.set(id, operation);
    return operation;
}

async function runExport(operation) {
    operation.status = 'running';
    const result = await prepareExport(
        dataRoot,
        operation.directory,
        operation.controller.signal,
    );
    operation.archivePath = result.archivePath;
    operation.archiveBytes = result.archiveBytes;
    operation.fileCount = result.fileCount;
    operation.filename = buildExportFileName();
    operation.status = 'ready';
}

function findOwnedOperation(request, kind) {
    const id = requireOperationId(request.params.id);
    const operation = operations.get(id);
    if (!operation || operation.owner !== ownerFor(request) || (kind && operation.kind !== kind)) {
        throw new LauncherError('OPERATION_NOT_FOUND', 'Operation not found or expired', 404);
    }
    return operation;
}

function publicOperation(operation) {
    const result = {
        id: operation.id,
        kind: operation.kind,
        status: operation.status,
        createdAt: operation.createdAt,
    };
    if (operation.filename) result.filename = operation.filename;
    if (operation.archiveBytes != null) result.size = operation.archiveBytes;
    if (operation.fileCount != null) result.fileCount = operation.fileCount;
    if (operation.entryCount != null) result.entryCount = operation.entryCount;
    if (operation.error) result.error = operation.error;
    if (operation.kind === 'export' && operation.status === 'ready') {
        result.downloadUrl = `/api/plugins/st-launcher/exports/${operation.id}/archive`;
    }
    return result;
}

function normalizeError(error) {
    return {
        code: error?.code || 'EXPORT_FAILED',
        message: error?.message || 'Export failed',
        retryable: error?.retryable ?? false,
    };
}

function ownerFor(request) {
    return request.user?.profile?.handle || 'default-user';
}

function buildExportFileName() {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    return `sillytavern_backup_${timestamp}.tar.gz`;
}

function scheduleExpiry(operation) {
    const timer = setTimeout(() => {
        removeOperation(operation).catch(error => {
            console.error('[ST Launcher] Failed to expire operation:', error);
        });
    }, OPERATION_TTL_MS);
    timer.unref?.();
}

async function removeOperation(operation) {
    if (activeOperation === operation) activeOperation = null;
    operations.delete(operation.id);
    await fs.rm(operation.directory, { recursive: true, force: true });
}

async function cleanupExpiredOperations() {
    const now = Date.now();
    const entries = await fs.readdir(operationsRoot, { withFileTypes: true });
    await Promise.all(entries
        .filter(entry => entry.isDirectory() && /^[0-9a-fA-F-]{36}$/.test(entry.name))
        .map(async entry => {
            const target = path.join(operationsRoot, entry.name);
            const stat = await fs.stat(target);
            if (now - stat.mtimeMs > OPERATION_TTL_MS) {
                await fs.rm(target, { recursive: true, force: true });
            }
        }));
}
