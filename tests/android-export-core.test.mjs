import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import test from 'node:test';

import {
    createDataSnapshot,
    prepareDataArchive,
} from '../android/app/src/main/assets/sillytavern_overlay/src/android-export-core.js';

const execFileAsync = promisify(execFile);

test('snapshot hard links survive source backup rotation', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-export-snapshot-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));

    const source = path.join(root, 'source');
    const snapshot = path.join(root, 'snapshot');
    const backup = path.join(source, 'backups', 'chat_test_20260720-132103.jsonl');
    await fs.mkdir(path.dirname(backup), { recursive: true });
    await fs.writeFile(backup, '{"mes":"preserved"}\n');

    const result = await createDataSnapshot(source, snapshot);
    await fs.unlink(backup);

    assert.equal(result.fileCount, 1);
    assert.equal(await fs.readFile(path.join(snapshot, 'backups', path.basename(backup)), 'utf8'), '{"mes":"preserved"}\n');
});

test('prepared export is complete and excludes transient uploads', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'st-export-archive-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));

    const data = path.join(root, 'data');
    const work = path.join(root, 'work');
    await fs.mkdir(path.join(data, 'default-user', 'chats'), { recursive: true });
    await fs.mkdir(path.join(data, 'temp_uploads'), { recursive: true });
    await fs.writeFile(path.join(data, 'default-user', 'chats', 'chat.jsonl'), '{"mes":"hello"}\n');
    await fs.writeFile(path.join(data, 'temp_uploads', 'partial-upload'), 'temporary');

    const result = await prepareDataArchive(data, work, 'test-export');
    const { stdout } = await execFileAsync('tar', ['-tzf', result.archivePath]);

    assert.ok(result.archiveBytes > 0);
    assert.match(stdout, /data\/default-user\/chats\/chat\.jsonl/);
    assert.doesNotMatch(stdout, /temp_uploads/);
});
