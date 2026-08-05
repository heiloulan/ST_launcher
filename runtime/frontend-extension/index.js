/**
 * ST Launcher controls shown inside SillyTavern's extension settings.
 *
 * Native-only actions use the exact-origin WebMessage bridge installed by the
 * Android shell. No privileged object is exposed to remote frames.
 */
import { getRequestHeaders } from '../../../../script.js';

const API_ROOT = '/api/plugins/st-launcher';
const OPERATION_TIMEOUT_MS = 30 * 60 * 1000;
const POLL_INTERVAL_MS = 500;

jQuery(() => {
    if (document.getElementById('st_launcher_panel')) return;

    $('#extensions_settings2').append(`
        <div id="st_launcher_panel" class="backend-panel">
            <div class="inline-drawer">
                <div class="inline-drawer-toggle inline-drawer-header">
                    <b>后台功能项</b>
                    <div class="inline-drawer-icon fa-solid fa-circle-chevron-down down"></div>
                </div>
                <div class="inline-drawer-content">
                    <div class="backend-panel">
                        <button id="st_launcher_export" class="backend-btn">
                            <i class="fa fa-download"></i> 导出用户数据
                        </button>
                        <button id="st_launcher_import" class="backend-btn">
                            <i class="fa fa-upload"></i> 导入用户数据
                        </button>
                        <button id="st_launcher_logs" class="backend-btn">
                            <i class="fa fa-terminal"></i> 查看服务器日志
                        </button>
                    </div>
                </div>
            </div>
        </div>
    `);

    $('#st_launcher_export').on('click', handleExport);
    $('#st_launcher_import').on('click', confirmImport);
    $('#st_launcher_logs').on('click', openLogViewer);
});

function nativeRequest(type, payload = {}) {
    if (!window.STLauncherApi?.request) {
        throw new Error('此操作需要在 ST Launcher 应用内完成');
    }
    return window.STLauncherApi.request(type, payload);
}

async function responseError(response) {
    const text = await response.text();
    try {
        const payload = JSON.parse(text);
        return payload?.error?.message || payload?.error || text;
    } catch {
        return text || `HTTP ${response.status}`;
    }
}

async function requestJson(url, options = {}) {
    const response = await fetch(url, {
        ...options,
        headers: {
            ...getRequestHeaders(),
            ...(options.headers || {}),
        },
    });
    if (!response.ok) {
        throw new Error(await responseError(response));
    }
    return response.json();
}

async function waitForOperation(id) {
    const deadline = Date.now() + OPERATION_TIMEOUT_MS;
    while (Date.now() < deadline) {
        const operation = await requestJson(`${API_ROOT}/operations/${id}`);
        if (operation.status === 'ready') return operation;
        if (operation.status === 'failed' || operation.status === 'cancelled') {
            throw new Error(operation.error?.message || `任务状态：${operation.status}`);
        }
        await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
    }
    throw new Error('操作超时，请重试');
}

async function handleExport() {
    const button = $('#st_launcher_export');
    const original = button.html();
    button.html('<i class="fa fa-spinner fa-spin"></i> 正在打包…').prop('disabled', true);
    try {
        const created = await requestJson(`${API_ROOT}/exports`, { method: 'POST' });
        const operation = await waitForOperation(created.id);
        if (!operation.downloadUrl || !operation.filename) {
            throw new Error('服务器返回了无效的导出任务');
        }

        if (window.STLauncherApi?.request) {
            await nativeRequest('download.url', {
                url: operation.downloadUrl,
                fileName: operation.filename,
                mimeType: 'application/gzip',
                cancelUrl: `${API_ROOT}/operations/${operation.id}`,
            });
            toastr.info('数据打包完成，请选择保存位置');
        } else {
            const anchor = document.createElement('a');
            anchor.href = operation.downloadUrl;
            anchor.download = operation.filename;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
        }
    } catch (error) {
        console.error('[ST Launcher] Export failed', error);
        toastr.error(`导出失败：${error.message}`);
    } finally {
        button.html(original).prop('disabled', false);
    }
}

function confirmImport() {
    const overlay = document.createElement('div');
    overlay.className = 'import-confirm-overlay';
    overlay.innerHTML = `
        <div class="import-confirm-dialog">
            <h3>确认导入</h3>
            <p>备份会先在隔离目录中完成安全检查。只有检查通过后才替换当前数据；新数据无法启动时会自动回滚。</p>
            <div class="import-confirm-buttons">
                <button class="confirm-no">取消</button>
                <button class="confirm-yes">选择备份</button>
            </div>
        </div>
    `;
    document.documentElement.appendChild(overlay);
    overlay.addEventListener('click', event => event.stopPropagation());
    overlay.addEventListener('touchstart', event => event.stopPropagation());
    overlay.querySelector('.confirm-no').addEventListener('click', () => overlay.remove());
    overlay.querySelector('.confirm-yes').addEventListener('click', async () => {
        overlay.remove();
        try {
            await nativeRequest('backup.import.pick');
            toastr.info('请选择 .tar.gz 或 .tgz 备份文件');
        } catch (error) {
            toastr.error(error.message);
        }
    });
}

async function openLogViewer() {
    // The launcher owns the log UI now: a native screen keeps text selection and
    // copying out of SillyTavern's global CSS and gesture handling, and stays
    // reachable when the page itself never loads.
    try {
        await nativeRequest('logs.open');
    } catch (error) {
        toastr.error(error.message);
    }
}
