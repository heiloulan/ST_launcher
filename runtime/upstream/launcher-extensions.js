import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import git from 'isomorphic-git';
import http from 'isomorphic-git/http/node';

const active = new Set();
const defaultsRoot = process.env.ST_LAUNCHER_DEFAULT_EXTENSIONS;
const globalsRoot = process.env.ST_LAUNCHER_GLOBAL_EXTENSIONS;

function failure(message, status = 400) {
    return Object.assign(new Error(message), { status });
}

export async function validateExtension(directory) {
    const manifest = JSON.parse(await fs.promises.readFile(path.join(directory, 'manifest.json'), 'utf8'));
    if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw failure('扩展 manifest 无效');
    for (const field of ['js', 'css']) {
        if (!manifest[field]) continue;
        const asset = path.resolve(directory, manifest[field]);
        if (!asset.startsWith(`${path.resolve(directory)}${path.sep}`)
            || !await fs.promises.stat(asset).then(stat => stat.isFile()).catch(() => false)) {
            throw failure(`扩展缺少 manifest 中声明的 ${field} 文件`);
        }
    }
    return manifest;
}

function stagingPaths(target) {
    const parent = process.env.ST_LAUNCHER_OPERATIONS_ROOT
        ? `${process.env.ST_LAUNCHER_OPERATIONS_ROOT}.extensions`
        : path.join(path.dirname(path.dirname(target)), '.st-launcher-extension-installs');
    const key = crypto.createHash('sha256').update(path.resolve(target)).digest('hex').slice(0, 24);
    return { parent, temporary: path.join(parent, `${key}.part`) };
}

export async function installExtension(url, target, branch, repair = false, clone = git.clone) {
    if (active.has(target)) throw failure('这个扩展正在安装，请稍后再试', 409);
    if (fs.existsSync(target) && !repair) throw failure('扩展目录已存在。如果上次安装失败，可在“后台功能项 → 修复扩展安装”中保留副本后重装。', 409);
    active.add(target);
    const { parent, temporary } = stagingPaths(target);
    try {
        await fs.promises.mkdir(parent, { recursive: true });
        // Only this launcher's staging area is eligible for automatic cleanup.
        await fs.promises.rm(temporary, { recursive: true, force: true });
        await clone({ fs, http, dir: temporary, url, ref: branch || undefined, depth: 1, singleBranch: true });
        const manifest = await validateExtension(temporary);
        await fs.promises.mkdir(path.dirname(target), { recursive: true });
        let preserved;
        if (fs.existsSync(target)) {
            if (!repair) throw failure('目标扩展已存在，现有内容已保留', 409);
            preserved = path.join(parent, `saved-${path.basename(target)}-${crypto.randomUUID()}`);
            await fs.promises.rename(target, preserved);
        }
        try {
            await fs.promises.rename(temporary, target);
        } catch (error) {
            if (preserved && !fs.existsSync(target)) await fs.promises.rename(preserved, target);
            throw error;
        }
        return { ...manifest, preserved };
    } finally {
        try { await fs.promises.rm(temporary, { recursive: true, force: true }); }
        finally { active.delete(target); }
    }
}

export function defaultExtension(target) {
    if (!defaultsRoot || !globalsRoot || path.dirname(target) !== globalsRoot) return null;
    const bundled = path.join(defaultsRoot, path.basename(target));
    return fs.existsSync(path.join(bundled, 'manifest.json')) ? bundled : null;
}

export async function extensionVersion(target) {
    if (!fs.existsSync(target)) {
        const bundled = defaultExtension(target);
        if (!bundled) throw failure('扩展不存在', 404);
        const manifest = await validateExtension(bundled);
        return { currentBranchName: `内置 ${manifest.version}`, currentCommitHash: '', isUpToDate: true, remoteUrl: manifest.homePage || '' };
    }
    if (!fs.existsSync(path.join(target, '.git'))) {
        const manifest = await validateExtension(target);
        return { currentBranchName: `本地副本 ${manifest.version || ''}`, currentCommitHash: '', isUpToDate: true, remoteUrl: manifest.homePage || '' };
    }
    const currentCommitHash = await git.resolveRef({ fs, dir: target, ref: 'HEAD' });
    const currentBranchName = await git.currentBranch({ fs, dir: target }) || '';
    const remoteUrl = await git.getConfig({ fs, dir: target, path: 'remote.origin.url' }) || '';
    if (!remoteUrl || !currentBranchName) return { currentCommitHash, currentBranchName, remoteUrl, isUpToDate: true };
    const refs = await git.listServerRefs({ http, url: remoteUrl, prefix: `refs/heads/${currentBranchName}` });
    const remote = refs.find(ref => ref.ref === `refs/heads/${currentBranchName}`);
    return { currentCommitHash, currentBranchName, remoteUrl, isUpToDate: remote?.oid === currentCommitHash };
}

export async function updateExtension(target) {
    if (active.has(target)) throw failure('这个扩展正在处理，请稍后再试', 409);
    let before;
    if (!fs.existsSync(path.join(target, '.git'))) {
        before = await extensionVersion(target);
        if (!before.remoteUrl) throw failure('此本地扩展没有仓库地址，请使用“修复扩展安装”指定来源');
        await installExtension(before.remoteUrl, target, undefined, fs.existsSync(target));
    } else {
        active.add(target);
        try {
            before = await extensionVersion(target);
            if (!before.currentBranchName) throw failure('当前固定在提交上，请先选择分支');
            // Fast-forward only: a user's local changes are never force-reset.
            await git.fastForward({ fs, http, dir: target, ref: before.currentBranchName, singleBranch: true });
            await validateExtension(target);
        } finally { active.delete(target); }
    }
    const commit = await git.resolveRef({ fs, dir: target, ref: 'HEAD' });
    return { shortCommitHash: commit.slice(0, 7), extensionPath: target, isUpToDate: before.isUpToDate, remoteUrl: before.remoteUrl };
}

export async function extensionBranches(target) {
    const version = await extensionVersion(target);
    if (!fs.existsSync(path.join(target, '.git'))) throw failure('此扩展使用固定文件副本；手动更新后可以切换分支');
    const local = await git.listBranches({ fs, dir: target });
    const remote = version.remoteUrl ? await git.listServerRefs({ http, url: version.remoteUrl, prefix: 'refs/heads/' }) : [];
    const result = await Promise.all(local.map(async name => ({
        current: name === version.currentBranchName, name, label: name,
        commit: await git.resolveRef({ fs, dir: target, ref: `refs/heads/${name}` }),
    })));
    result.push(...remote.map(ref => ({ current: false, name: `origin/${ref.ref.slice(11)}`, label: ref.ref.slice(11), commit: ref.oid })));
    return result;
}

export async function switchExtension(target, branch) {
    if (active.has(target)) throw failure('这个扩展正在处理，请稍后再试', 409);
    active.add(target);
    try {
        if (!(await extensionBranches(target)).some(item => item.name === branch)) throw failure('分支不存在', 404);
        const ref = branch.replace(/^origin\//, '');
        if (branch.startsWith('origin/')) await git.fetch({ fs, http, dir: target, ref, singleBranch: true, depth: 1 });
        await git.checkout({ fs, dir: target, ref });
        await validateExtension(target);
    } finally { active.delete(target); }
}

export function bundledExtensions() {
    if (!defaultsRoot || !fs.existsSync(defaultsRoot)) return [];
    return fs.readdirSync(defaultsRoot).filter(name => fs.existsSync(path.join(defaultsRoot, name, 'manifest.json')))
        .map(name => ({ type: 'global', name: `third-party/${name}` }));
}

export async function restoreDefault(target) {
    if (active.has(target)) throw failure('这个扩展正在处理，请稍后再试', 409);
    const bundled = defaultExtension(target);
    if (!bundled || path.basename(target) === 'st-launcher') throw failure('此扩展没有可恢复的内置版本');
    active.add(target);
    try {
        const manifest = await validateExtension(bundled);
        if (fs.existsSync(target)) {
            const { parent } = stagingPaths(target);
            await fs.promises.mkdir(parent, { recursive: true });
            const preserved = path.join(parent, `saved-${path.basename(target)}-${crypto.randomUUID()}`);
            await fs.promises.rename(target, preserved);
        }
        return manifest;
    } finally { active.delete(target); }
}
