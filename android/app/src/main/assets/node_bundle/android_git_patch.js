/**
 * Android Git Spawn Patch
 * 
 * On Android 10+, execve() is blocked for files in the app's data directory
 * (W^X / noexec policy). Node.js child_process.spawn('git') fails with EACCES.
 * 
 * This preload script monkey-patches child_process.spawn and child_process.spawnSync
 * to intercept calls to 'git' and route them through /system/bin/linker64,
 * which bypasses the W^X restriction (same trick used for the Node.js binary itself).
 */
'use strict';

const childProcess = require('child_process');
const path = require('path');

const LINKER = '/system/bin/linker64';
const GIT_REAL_BIN = process.env.GIT_REAL_BIN; // Absolute path to git.bin

if (GIT_REAL_BIN) {
    const originalSpawn = childProcess.spawn;
    const originalSpawnSync = childProcess.spawnSync;

    childProcess.spawn = function patchedSpawn(command, args, options) {
        if (command === 'git' || (typeof command === 'string' && command.endsWith('/git'))) {
            // Rewrite: spawn('git', ['clone', ...]) → spawn('/system/bin/linker64', ['git.bin', 'clone', ...])
            const newArgs = [GIT_REAL_BIN, ...(Array.isArray(args) ? args : [])];
            return originalSpawn.call(this, LINKER, newArgs, options);
        }
        return originalSpawn.apply(this, arguments);
    };

    childProcess.spawnSync = function patchedSpawnSync(command, args, options) {
        if (command === 'git' || (typeof command === 'string' && command.endsWith('/git'))) {
            const newArgs = [GIT_REAL_BIN, ...(Array.isArray(args) ? args : [])];
            return originalSpawnSync.call(this, LINKER, newArgs, options);
        }
        return originalSpawnSync.apply(this, arguments);
    };
}
