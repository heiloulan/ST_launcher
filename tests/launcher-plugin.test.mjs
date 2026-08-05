import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { finished } from 'node:stream/promises';
import test from 'node:test';
import zlib from 'node:zlib';

import { stageImport } from '../runtime/plugin-st-launcher/importer.js';
import { exit, init } from '../runtime/plugin-st-launcher/index.js';
import { storageBudget } from '../runtime/plugin-st-launcher/paths.js';
import {
    createStableSnapshot,
    prepareExport,
} from '../runtime/plugin-st-launcher/snapshot.js';

const requireFromPlugin = createRequire(
    new URL('../runtime/plugin-st-launcher/package.json', import.meta.url),
);
const tar = requireFromPlugin('tar-stream');

async function makeArchive(entries) {
    const pack = tar.pack();
    const chunks = [];
    pack.on('data', chunk => chunks.push(chunk));
    for (const entry of entries) {
        await new Promise((resolve, reject) => {
            pack.entry(entry.header, entry.body || Buffer.alloc(0), error => {
                if (error) reject(error); else resolve();
            });
        });
    }
    pack.finalize();
    await finished(pack);
    return zlib.gzipSync(Buffer.concat(chunks));
}

async function listArchive(archivePath) {
    const compressed = await fs.readFile(archivePath);
    const extract = tar.extract();
    const names = [];
    extract.on('entry', (header, stream, next) => {
        names.push(header.name);
        stream.resume();
        stream.once('end', next);
    });
    extract.end(zlib.gunzipSync(compressed));
    await finished(extract);
    return names;
}

test('snapshot is independent from later source mutation and deletion', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-snapshot-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    const source = path.join(root, 'source');
    const snapshot = path.join(root, 'snapshot');
    const chat = path.join(source, 'default-user', 'chats', 'chat.jsonl');
    await fs.mkdir(path.dirname(chat), { recursive: true });
    await fs.writeFile(chat, '{"mes":"before"}\n');

    await createStableSnapshot(source, snapshot);
    await fs.writeFile(chat, '{"mes":"after"}\n');
    await fs.unlink(chat);

    assert.equal(
        await fs.readFile(path.join(snapshot, 'default-user', 'chats', 'chat.jsonl'), 'utf8'),
        '{"mes":"before"}\n',
    );
});

test('export keeps the legacy data root and excludes transient runtime files', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-export-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    const data = path.join(root, 'data');
    const operation = path.join(root, 'operation');
    await fs.mkdir(path.join(data, 'default-user', 'chats'), { recursive: true });
    await fs.mkdir(path.join(data, 'default-user', 'empty'), { recursive: true });
    await fs.mkdir(path.join(data, 'temp_uploads'), { recursive: true });
    await fs.mkdir(path.join(data, '_webpack', 'cache'), { recursive: true });
    await fs.writeFile(path.join(data, 'default-user', 'chats', 'chat.jsonl'), 'hello');
    await fs.writeFile(path.join(data, 'temp_uploads', 'partial'), 'temporary');
    await fs.writeFile(path.join(data, '_webpack', 'cache', 'pack'), 'generated');

    const result = await prepareExport(data, operation, new AbortController().signal);
    const entries = await listArchive(result.archivePath);

    assert.ok(result.archiveBytes > 0);
    assert.ok(entries.includes('data/default-user/chats/chat.jsonl'));
    assert.ok(entries.includes('data/default-user/empty/'));
    assert.ok(!entries.some(name => name.includes('temp_uploads')));
    assert.ok(!entries.some(name => name.includes('_webpack')));
});

test('export refuses to silently omit a user-data symlink', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-export-link-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    const data = path.join(root, 'data');
    await fs.mkdir(data);
    await fs.symlink('/etc/passwd', path.join(data, 'link'));

    await assert.rejects(
        prepareExport(data, path.join(root, 'operation'), new AbortController().signal),
        error => error.code === 'UNSAFE_DATA',
    );
});

test('valid import is staged without touching live data', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-import-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    const operation = path.join(root, 'operation');
    await fs.mkdir(operation, { recursive: true });
    const archive = await makeArchive([
        { header: { name: 'data/', type: 'directory' } },
        {
            header: {
                name: 'data/default-user/chats/chat.jsonl',
                type: 'file',
                size: 5,
            },
            body: Buffer.from('hello'),
        },
    ]);

    const metadata = await stageImport(Readable.from(archive), operation, crypto.randomUUID());

    assert.equal(metadata.status, 'ready');
    assert.equal(
        await fs.readFile(
            path.join(operation, 'candidate', 'data', 'default-user', 'chats', 'chat.jsonl'),
            'utf8',
        ),
        'hello',
    );
});

test('import rejects path traversal and links', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-import-unsafe-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    const fixtures = [
        [{ header: { name: '../escape', type: 'file', size: 3 }, body: Buffer.from('bad') }],
        [{ header: { name: 'data/link', type: 'symlink', linkname: '/etc/passwd' } }],
    ];

    for (const entries of fixtures) {
        const operation = path.join(root, crypto.randomUUID());
        await fs.mkdir(operation, { recursive: true });
        const archive = await makeArchive(entries);
        await assert.rejects(
            stageImport(Readable.from(archive), operation, path.basename(operation)),
            error => error.code === 'UNSAFE_ARCHIVE',
        );
    }
});

test('truncated gzip import fails and removes staging', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-import-truncated-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    const operation = path.join(root, crypto.randomUUID());
    await fs.mkdir(operation, { recursive: true });
    const archive = await makeArchive([
        { header: { name: 'data/', type: 'directory' } },
        { header: { name: 'data/file', type: 'file', size: 5 }, body: Buffer.from('hello') },
    ]);

    await assert.rejects(
        stageImport(
            Readable.from(archive.subarray(0, Math.floor(archive.length / 2))),
            operation,
            path.basename(operation),
        ),
    );
    await assert.rejects(fs.stat(operation));
});

test('cancelled snapshot stops before copying user data', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-cancelled-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    const source = path.join(root, 'source');
    await fs.mkdir(source);
    await fs.writeFile(path.join(source, 'data.json'), 'content');
    const controller = new AbortController();
    controller.abort();

    await assert.rejects(
        createStableSnapshot(source, path.join(root, 'snapshot'), controller.signal),
        error => error.code === 'OPERATION_CANCELLED',
    );
});

test('storage budget keeps the larger fixed or percentage reserve', async () => {
    const gibibyte = 1024 ** 3;
    const noSpace = {
        statfs: async () => ({
            bsize: 4096,
            blocks: (20 * gibibyte) / 4096,
            bavail: gibibyte / 4096,
        }),
    };
    assert.equal(await storageBudget(noSpace, '/unused'), 0);

    const available = {
        statfs: async () => ({
            bsize: 4096,
            blocks: (2 * gibibyte) / 4096,
            bavail: gibibyte / 4096,
        }),
    };
    assert.equal(await storageBudget(available, '/unused'), gibibyte - 512 * 1024 * 1024);
});

test('server plugin holds the single transaction slot until an export is removed', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-plugin-routes-'));
    const data = path.join(root, 'data');
    const operations = path.join(root, 'operations');
    await fs.mkdir(data);
    const previousOperations = process.env.ST_LAUNCHER_OPERATIONS_ROOT;
    const previousDataRoot = globalThis.DATA_ROOT;
    process.env.ST_LAUNCHER_OPERATIONS_ROOT = operations;
    globalThis.DATA_ROOT = data;
    t.after(async () => {
        await exit();
        await new Promise(resolve => setTimeout(resolve, 25));
        if (previousOperations === undefined) {
            delete process.env.ST_LAUNCHER_OPERATIONS_ROOT;
        } else {
            process.env.ST_LAUNCHER_OPERATIONS_ROOT = previousOperations;
        }
        if (previousDataRoot === undefined) {
            delete globalThis.DATA_ROOT;
        } else {
            globalThis.DATA_ROOT = previousDataRoot;
        }
        await fs.rm(root, { recursive: true, force: true });
    });

    const routes = new Map();
    const router = {
        get: (route, handler) => routes.set(`GET ${route}`, handler),
        post: (route, handler) => routes.set(`POST ${route}`, handler),
        delete: (route, handler) => routes.set(`DELETE ${route}`, handler),
    };
    await init(router);
    const exportHandler = routes.get('POST /exports');
    const first = fakeResponse();
    const second = fakeResponse();

    exportHandler({}, first);
    exportHandler({}, second);

    assert.equal(first.statusCode, 202);
    assert.equal(second.statusCode, 409);
    assert.equal(second.body.error.code, 'OPERATION_BUSY');

    const getOperation = routes.get('GET /operations/:id');
    let operation;
    for (let attempt = 0; attempt < 100; attempt++) {
        const response = fakeResponse();
        getOperation({ params: { id: first.body.id } }, response);
        operation = response.body;
        if (operation.status === 'ready') break;
        await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(operation.status, 'ready');

    const whileReady = fakeResponse();
    exportHandler({}, whileReady);
    assert.equal(whileReady.statusCode, 409);

    const removeOperation = routes.get('DELETE /operations/:id');
    const removed = fakeResponse();
    await removeOperation({ params: { id: first.body.id } }, removed);
    assert.equal(removed.statusCode, 204);

    const afterRemoval = fakeResponse();
    exportHandler({}, afterRemoval);
    assert.equal(afterRemoval.statusCode, 202);
});

function fakeResponse() {
    return {
        statusCode: 200,
        body: null,
        headersSent: false,
        status(value) {
            this.statusCode = value;
            return this;
        },
        json(value) {
            this.body = value;
            this.headersSent = true;
            return this;
        },
        sendStatus(value) {
            this.statusCode = value;
            this.headersSent = true;
            return this;
        },
        destroy(error) {
            throw error;
        },
    };
}
