(() => {
    'use strict';

    if (window.__stLauncherBridgeInstalled || !window.STLauncher) {
        return;
    }
    window.__stLauncherBridgeInstalled = true;

    const pending = new Map();
    const encoderChunkBytes = 256 * 1024;
    const requestTimeoutMs = 60 * 1000;

    function makeId() {
        if (crypto.randomUUID) {
            return crypto.randomUUID();
        }
        return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    }

    function request(type, payload = {}) {
        const id = makeId();
        const promise = new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                pending.delete(id);
                reject(new Error(`Android request timed out: ${type}`));
            }, requestTimeoutMs);
            pending.set(id, { resolve, reject, timer });
        });
        try {
            window.STLauncher.postMessage(JSON.stringify({
                version: 1,
                id,
                type,
                payload,
            }));
        } catch (error) {
            const handler = pending.get(id);
            clearTimeout(handler?.timer);
            pending.delete(id);
            throw error;
        }
        return promise;
    }

    window.STLauncher.addEventListener('message', event => {
        try {
            const response = JSON.parse(event.data);
            if (response.version !== 1 || !response.id) {
                return;
            }
            const handler = pending.get(response.id);
            if (!handler) {
                return;
            }
            pending.delete(response.id);
            clearTimeout(handler.timer);
            if (response.ok) {
                handler.resolve(response.result || {});
            } else {
                const error = new Error(response.error?.message || 'Android request failed');
                error.code = response.error?.code || 'ANDROID_REQUEST_FAILED';
                error.retryable = response.error?.retryable === true;
                handler.reject(error);
            }
        } catch (error) {
            console.error('[ST Launcher] Invalid native response', error);
        }
    });

    function bytesToBase64(bytes) {
        let binary = '';
        const step = 32 * 1024;
        for (let offset = 0; offset < bytes.length; offset += step) {
            binary += String.fromCharCode(...bytes.subarray(offset, offset + step));
        }
        return btoa(binary);
    }

    async function downloadBlob(url, fileName = 'download', fallbackMime = 'application/octet-stream') {
        const transferId = makeId();
        try {
            const response = await fetch(url);
            if (!response.ok && !url.startsWith('blob:') && !url.startsWith('data:')) {
                throw new Error(`Download returned HTTP ${response.status}`);
            }
            const blob = await response.blob();
            await request('blob.start', {
                transferId,
                fileName,
                mimeType: blob.type || fallbackMime,
                size: blob.size,
            });

            const reader = blob.stream().getReader();
            let sequence = 0;
            let buffered = new Uint8Array(0);
            while (true) {
                const { value, done } = await reader.read();
                if (done) break;
                const merged = new Uint8Array(buffered.length + value.length);
                merged.set(buffered);
                merged.set(value, buffered.length);
                let offset = 0;
                while (merged.length - offset >= encoderChunkBytes) {
                    const chunk = merged.subarray(offset, offset + encoderChunkBytes);
                    await request('blob.chunk', {
                        transferId,
                        sequence: sequence++,
                        data: bytesToBase64(chunk),
                    });
                    offset += encoderChunkBytes;
                }
                buffered = merged.slice(offset);
            }
            if (buffered.length > 0) {
                await request('blob.chunk', {
                    transferId,
                    sequence,
                    data: bytesToBase64(buffered),
                });
            }
            await request('blob.finish', { transferId });
        } catch (error) {
            await request('blob.cancel', { transferId }).catch(() => {});
            throw error;
        }
    }

    window.STLauncherApi = Object.freeze({
        request,
        downloadBlob,
    });
    window.__stLauncherDownloadBlob = downloadBlob;

    const originalClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function patchedClick() {
        if (this.download && /^(blob:|data:)/.test(this.href)) {
            downloadBlob(this.href, this.download).catch(error => {
                console.error('[ST Launcher] Blob download failed', error);
                request('toast.show', { message: `文件处理失败: ${error.message}` }).catch(() => {});
            });
            return;
        }
        return originalClick.call(this);
    };

    document.addEventListener('click', event => {
        const anchor = event.target?.closest?.('a[download]');
        if (!anchor || !/^(blob:|data:)/.test(anchor.href)) {
            return;
        }
        event.preventDefault();
        event.stopPropagation();
        downloadBlob(anchor.href, anchor.download || 'download').catch(error => {
            console.error('[ST Launcher] Blob download failed', error);
        });
    }, true);

    function reportTheme() {
        const meta = document.querySelector('meta[name="theme-color"]');
        const color = meta?.content || (document.body && getComputedStyle(document.body).backgroundColor);
        if (color) {
            request('theme.set', { color }).catch(() => {});
        }
    }

    const observer = new MutationObserver(reportTheme);
    const beginThemeObservation = () => {
        if (document.head) {
            observer.observe(document.head, {
                subtree: true,
                childList: true,
                attributes: true,
                attributeFilter: ['content'],
            });
        }
        if (document.body) {
            observer.observe(document.body, {
                attributes: true,
                attributeFilter: ['class', 'style'],
            });
        }
        reportTheme();
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', beginThemeObservation, { once: true });
    } else {
        beginThemeObservation();
    }
})();
