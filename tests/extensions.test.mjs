import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import test from 'node:test';
import { installExtension } from '../runtime/upstream/launcher-extensions.js';

async function completeClone({dir}) {
    await fs.mkdir(dir, {recursive:true});
    await fs.writeFile(path.join(dir,'manifest.json'),JSON.stringify({version:'1.0',js:'index.js'}));
    await fs.writeFile(path.join(dir,'index.js'),'// extension');
}

test('interrupted clone never publishes a directory and the same URL can be retried', async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(),'st-ext-retry-'));
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    const target=path.join(root,'extensions','helper');
    await assert.rejects(installExtension('https://example.test/helper',target,undefined,false,async ({dir})=>{
        await fs.mkdir(dir,{recursive:true});
        await fs.writeFile(path.join(dir,'partial'),'incomplete');
        throw new Error('network interrupted');
    }),/network interrupted/);
    assert.equal(await fs.stat(target).catch(()=>null),null);
    const result=await installExtension('https://example.test/helper',target,undefined,false,completeClone);
    assert.equal(result.version,'1.0');
    assert.equal(await fs.readFile(path.join(target,'index.js'),'utf8'),'// extension');
});

test('same-target concurrent installation is rejected without disturbing the first install', async t => {
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'st-ext-concurrent-'));
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    const target=path.join(root,'extensions','helper');
    let release;
    let entered;
    const started=new Promise(resolve=>{entered=resolve;});
    const blocked=new Promise(resolve=>{release=resolve;});
    const first=installExtension('https://example.test/helper',target,undefined,false,async options=>{
        entered();await blocked;await completeClone(options);
    });
    await started;
    await assert.rejects(installExtension('https://example.test/helper',target,undefined,false,completeClone),error=>error.status===409);
    release();await first;
    assert.equal(await fs.readFile(path.join(target,'index.js'),'utf8'),'// extension');
});

test('invalid manifest cleans staging and explicit repair preserves existing files', async t => {
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'st-ext-repair-'));
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    const target=path.join(root,'extensions','helper');
    await assert.rejects(installExtension('https://example.test/helper',target,undefined,false,async ({dir})=>{
        await fs.mkdir(dir,{recursive:true});
        await fs.writeFile(path.join(dir,'manifest.json'),'{"js":"missing.js"}');
    }),/缺少/);
    await fs.mkdir(target,{recursive:true});
    await fs.writeFile(path.join(target,'user-file'),'preserve');
    await assert.rejects(installExtension('https://example.test/helper',target,undefined,false,completeClone),error=>error.status===409);
    const result=await installExtension('https://example.test/helper',target,undefined,true,completeClone);
    assert.equal(await fs.readFile(path.join(result.preserved,'user-file'),'utf8'),'preserve');
});
