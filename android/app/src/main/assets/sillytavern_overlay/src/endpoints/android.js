import crypto from 'node:crypto';
import express from 'express';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { prepareDataArchive } from '../android-export-core.js';

const fsPromises = fs.promises;
const EXPORT_TTL_MS = 15 * 60 * 1000;

export const router = express.Router();

const homeDirectory = process.env.HOME || process.cwd();
const uploadDirectory = path.join(process.cwd(), 'data', 'temp_uploads');
const exportDirectory = path.join(homeDirectory, 'prepared_exports');
const preparedExports = new Map();
let exportInProgress = false;

fs.mkdirSync(uploadDirectory, { recursive: true });
fs.mkdirSync(exportDirectory, { recursive: true });

function getOwner(request) {
    return request.user?.profile?.handle || 'default-user';
}

function buildExportFileName() {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    return `sillytavern_backup_${timestamp}.tar.gz`;
}

async function removePreparedExport(id) {
    const job = preparedExports.get(id);
    preparedExports.delete(id);
    if (job?.archivePath) {
        await fsPromises.rm(job.archivePath, { force: true }).catch(() => {});
    }
}

async function cleanupOrphanedExports() {
    // Jobs only live in memory. After a Node restart, every artifact in this
    // dedicated directory is unreachable and can be removed immediately.
    const entries = await fsPromises.readdir(exportDirectory, { withFileTypes: true });
    await Promise.all(entries.map(entry => fsPromises.rm(
        path.join(exportDirectory, entry.name),
        { recursive: true, force: true },
    )));
}

const orphanCleanup = cleanupOrphanedExports().catch(error => {
    console.warn('[Android Export] Failed to clean orphaned exports:', error);
});

async function createPreparedExport(request) {
    await orphanCleanup;

    if (exportInProgress) {
        const error = new Error('Another data export is already being prepared');
        error.code = 'EXPORT_BUSY';
        throw error;
    }

    const dataDirectory = path.join(process.cwd(), 'data');
    if (!fs.existsSync(dataDirectory)) {
        const error = new Error('Data directory not found');
        error.code = 'DATA_NOT_FOUND';
        throw error;
    }

    exportInProgress = true;
    const id = crypto.randomUUID();

    try {
        console.info(`[Android Export] Preparing snapshot ${id}`);
        const result = await prepareDataArchive(dataDirectory, exportDirectory, id);
        const job = {
            id,
            owner: getOwner(request),
            filename: buildExportFileName(),
            createdAt: Date.now(),
            downloading: false,
            ...result,
        };

        preparedExports.set(id, job);
        const expiryTimer = setTimeout(() => removePreparedExport(id), EXPORT_TTL_MS);
        expiryTimer.unref?.();

        if (job.skipped.length > 0) {
            console.warn(`[Android Export] Snapshot skipped ${job.skipped.length} vanished or unsupported entries`);
        }
        console.info(
            `[Android Export] Prepared ${id}: ${job.fileCount} files, `
            + `${job.archiveBytes} archive bytes`,
        );
        return job;
    } finally {
        exportInProgress = false;
    }
}

function sendExportError(response, error) {
    console.error('[Android Export] Failed:', error);

    if (response.headersSent) {
        response.destroy(error);
        return;
    }

    const status = error?.code === 'EXPORT_BUSY'
        ? 409
        : error?.code === 'DATA_NOT_FOUND'
            ? 404
            : error?.code === 'INSUFFICIENT_STORAGE'
                ? 507
                : 500;
    response.status(status).json({ error: error?.message || 'Export failed' });
}

function streamPreparedExport(request, response, job) {
    if (!job || job.owner !== getOwner(request)) {
        return response.status(404).json({ error: 'Prepared export not found or expired' });
    }
    if (job.downloading) {
        return response.status(409).json({ error: 'Prepared export is already being downloaded' });
    }

    job.downloading = true;
    response.setHeader('Content-Type', 'application/gzip');
    response.setHeader('Content-Disposition', `attachment; filename="${job.filename}"`);
    response.setHeader('Content-Length', String(job.archiveBytes));
    response.setHeader('Cache-Control', 'no-store');

    const archiveStream = fs.createReadStream(job.archivePath);
    let finished = false;

    archiveStream.on('error', error => {
        job.downloading = false;
        sendExportError(response, error);
    });
    response.on('finish', () => {
        finished = true;
        console.info(`[Android Export] Download completed: ${job.id}`);
        removePreparedExport(job.id);
    });
    response.on('close', () => {
        if (!finished) {
            job.downloading = false;
            console.warn(`[Android Export] Download interrupted: ${job.id}`);
        }
    });

    archiveStream.pipe(response);
}

/**
 * Prepares a stable archive before the Android document picker is opened.
 */
router.post('/prepare-export', async (request, response) => {
    try {
        const job = await createPreparedExport(request);
        response.json({
            id: job.id,
            filename: job.filename,
            size: job.archiveBytes,
            downloadUrl: `/api/android/export-data/${job.id}`,
        });
    } catch (error) {
        sendExportError(response, error);
    }
});

/**
 * Streams a previously prepared archive. The archive is deleted after a
 * complete response, or automatically after the expiry period.
 */
router.get('/export-data/:id', (request, response) => {
    streamPreparedExport(request, response, preparedExports.get(request.params.id));
});

/**
 * Compatibility endpoint for non-Android clients. Unlike the old
 * implementation, it waits for tar to finish successfully before sending 200.
 */
router.post('/export-data', async (request, response) => {
    try {
        const job = await createPreparedExport(request);
        streamPreparedExport(request, response, job);
    } catch (error) {
        sendExportError(response, error);
    }
});

/**
 * Receives a raw tar.gz stream and extracts it into the SillyTavern directory.
 */
router.post('/import-data', async (request, response) => {
    if (exportInProgress) {
        return response.status(409).send('An export is currently being prepared');
    }

    const uploadedFile = path.join(uploadDirectory, `import_${Date.now()}.tar.gz`);
    const writeStream = fs.createWriteStream(uploadedFile);
    let settled = false;

    const fail = (status, prefix, error) => {
        if (settled) {
            return;
        }
        settled = true;
        console.error(`[Android Import] ${prefix}:`, error);
        fs.rmSync(uploadedFile, { force: true });
        if (!response.headersSent) {
            response.status(status).send(`${prefix}: ${error.message}`);
        }
    };

    request.on('error', error => fail(500, 'Upload failed', error));
    writeStream.on('error', error => fail(500, 'Write failed', error));
    request.pipe(writeStream);

    writeStream.on('finish', () => {
        if (settled) {
            return;
        }
        const tar = spawn('tar', ['-xzf', uploadedFile, '-C', process.cwd()]);
        let stderr = '';

        tar.stderr.setEncoding('utf8');
        tar.stderr.on('data', chunk => {
            stderr = (stderr + chunk).slice(-64 * 1024);
        });
        tar.on('error', error => fail(500, 'Import failed', error));
        tar.on('close', code => {
            if (settled) {
                return;
            }
            settled = true;
            fs.rmSync(uploadedFile, { force: true });
            if (code !== 0) {
                console.error(`[Android Import] tar exited with code ${code}: ${stderr}`);
                return response.status(500).send(`Import failed: ${stderr.trim()}`);
            }

            console.info('[Android Import] Data imported successfully');
            response.json({ success: true, message: 'Data imported successfully' });
        });
    });
});

router.get('/log', async (_request, response) => {
    try {
        const logPath = path.join(homeDirectory, 'server.log');
        if (!fs.existsSync(logPath)) {
            return response.type('text').send('日志文件暂未生成，请等待服务启动...');
        }

        const content = await fsPromises.readFile(logPath, 'utf8');
        response.type('text').send(content.split('\n').slice(-200).join('\n'));
    } catch (error) {
        console.error('[Android Log] Error:', error);
        response.status(500).send(error.message);
    }
});
