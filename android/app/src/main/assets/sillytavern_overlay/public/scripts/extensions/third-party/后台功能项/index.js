/**
 * SillyTavern Android background tools.
 * Provides data export/import and server log viewing.
 */
import { getRequestHeaders } from '../../../../script.js';

const SERVER_LOG_API = '/api/android/log';

jQuery(() => {
    // Guard against duplicate extension initialization and event bindings.
    if (document.getElementById('backend_panel')) {
        return;
    }

    const panelHtml = `
    <div id="backend_panel" class="backend-panel">
        <div class="inline-drawer">
            <div class="inline-drawer-toggle inline-drawer-header">
                <b>后台功能项</b>
                <div class="inline-drawer-icon fa-solid fa-circle-chevron-down down"></div>
            </div>
            <div class="inline-drawer-content">
                <div class="backend-panel">
                    <button id="backend_export_btn" class="backend-btn" title="将用户数据打包导出">
                        <i class="fa fa-download"></i> 导出用户数据
                    </button>
                    <button id="backend_import_btn" class="backend-btn" title="导入之前备份的用户数据">
                        <i class="fa fa-upload"></i> 导入用户数据
                    </button>
                    <button id="backend_log_btn" class="backend-btn" title="查看 Node.js 服务器日志">
                        <i class="fa fa-terminal"></i> 查看服务器日志
                    </button>
                </div>
            </div>
        </div>
    </div>`;

    $('#extensions_settings2').append(panelHtml);
    $('#backend_export_btn').on('click', handleExportData);
    $('#backend_import_btn').on('click', handleImportData);
    $('#backend_log_btn').on('click', handleViewLog);
});

async function getErrorMessage(response) {
    const text = await response.text();
    try {
        return JSON.parse(text)?.error || text;
    } catch {
        return text;
    }
}

async function handleExportData() {
    const button = $('#backend_export_btn');
    const originalText = button.html();
    button.html('<i class="fa fa-spinner fa-spin"></i> 正在打包...').prop('disabled', true);

    try {
        // The server first creates a hard-link snapshot and a verified tar.gz.
        // No response is reported as successful until tar exits with code 0.
        const response = await fetch('/api/android/prepare-export', {
            method: 'POST',
            headers: getRequestHeaders(),
        });
        if (!response.ok) {
            throw new Error(await getErrorMessage(response));
        }

        const prepared = await response.json();
        if (!prepared?.id || !prepared?.filename || !prepared?.downloadUrl) {
            throw new Error('服务器返回了无效的导出任务');
        }

        if (window.Android && typeof window.Android.savePreparedExport === 'function') {
            const accepted = window.Android.savePreparedExport(
                prepared.id,
                prepared.filename,
                'application/gzip',
            );
            if (accepted === false) {
                throw new Error('Android 保存服务当前不可用');
            }
            toastr.info('数据打包完成，请选择保存位置');
            return;
        }

        // Desktop/fallback path: download the already prepared server file
        // directly. This avoids Blob, Base64 and a second full in-memory copy.
        const anchor = document.createElement('a');
        anchor.href = prepared.downloadUrl;
        anchor.download = prepared.filename;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        toastr.success('数据打包完成，下载已开始');
    } catch (error) {
        console.error('Export failed:', error);
        toastr.error(`导出失败: ${error.message}`);
    } finally {
        button.html(originalText).prop('disabled', false);
    }
}

function handleImportData() {
    return new Promise(resolve => {
        const overlay = document.createElement('div');
        overlay.className = 'import-confirm-overlay';
        overlay.innerHTML = `
            <div class="import-confirm-dialog">
                <h3>⚠️ 确认导入</h3>
                <p>导入将覆盖当前所有用户数据（角色卡、预设、聊天记录等），此操作不可撤销。<br><br>确定要继续吗？</p>
                <div class="import-confirm-buttons">
                    <button class="confirm-no">取消</button>
                    <button class="confirm-yes">确认导入</button>
                </div>
            </div>`;
        document.documentElement.appendChild(overlay);
        overlay.addEventListener('click', event => event.stopPropagation());
        overlay.addEventListener('touchstart', event => event.stopPropagation());

        overlay.querySelector('.confirm-no').addEventListener('click', () => {
            overlay.remove();
            resolve();
        });
        overlay.querySelector('.confirm-yes').addEventListener('click', () => {
            overlay.remove();
            doImport().then(resolve);
        });
    });
}

function doImport() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.tar.gz,.tgz';

    return new Promise(resolve => {
        input.addEventListener('change', async () => {
            if (!input.files?.length) {
                resolve();
                return;
            }

            try {
                toastr.info('正在导入数据，请稍候...');
                const requestHeaders = getRequestHeaders();
                const response = await fetch('/api/android/import-data', {
                    method: 'POST',
                    headers: {
                        'X-CSRF-Token': requestHeaders['X-CSRF-Token'] || '',
                        'Content-Type': 'application/octet-stream',
                    },
                    body: input.files[0],
                });

                if (!response.ok) {
                    throw new Error(await response.text());
                }

                toastr.success('数据导入成功！页面将在 3 秒后刷新...');
                setTimeout(() => location.reload(), 3000);
            } catch (error) {
                console.error('Import failed:', error);
                toastr.error(`导入失败: ${error.message}`);
            }
            resolve();
        });

        input.click();
    });
}

let logRefreshInterval = null;
let userIsScrolling = false;

function handleViewLog() {
    const modal = document.createElement('div');
    modal.className = 'log-modal-overlay';
    modal.innerHTML = `
        <div class="log-modal-header">
            <span>服务器日志</span>
            <div class="log-modal-actions">
                <button class="log-modal-copy" title="复制全部日志">📋 复制</button>
                <button class="log-modal-close">✕</button>
            </div>
        </div>
        <div class="log-modal-body" id="ext_log_content">加载中...</div>`;
    document.documentElement.appendChild(modal);
    modal.addEventListener('click', event => event.stopPropagation());
    modal.addEventListener('touchstart', event => event.stopPropagation());

    const logBody = modal.querySelector('.log-modal-body');
    logBody.addEventListener('scroll', () => {
        const atBottom = logBody.scrollTop + logBody.clientHeight >= logBody.scrollHeight - 50;
        userIsScrolling = !atBottom;
    });

    modal.querySelector('.log-modal-copy').addEventListener('click', () => {
        const text = logBody.textContent || '';
        navigator.clipboard.writeText(text)
            .then(() => toastr.success('日志已复制到剪贴板'))
            .catch(() => {
                const textarea = document.createElement('textarea');
                textarea.value = text;
                document.body.appendChild(textarea);
                textarea.select();
                document.execCommand('copy');
                textarea.remove();
                toastr.success('日志已复制到剪贴板');
            });
    });

    modal.querySelector('.log-modal-close').addEventListener('click', () => {
        clearInterval(logRefreshInterval);
        logRefreshInterval = null;
        userIsScrolling = false;
        modal.remove();
    });

    refreshLogContent();
    logRefreshInterval = setInterval(refreshLogContent, 2000);
}

async function refreshLogContent() {
    try {
        let logText = '';
        if (window.Android && window.Android.getLog) {
            logText = window.Android.getLog() || '暂无日志';
        } else {
            const response = await fetch(SERVER_LOG_API);
            if (response.ok) {
                logText = (await response.text()) || '暂无日志';
            }
        }

        const element = document.getElementById('ext_log_content');
        if (element) {
            element.textContent = logText;
            if (!userIsScrolling) {
                element.scrollTop = element.scrollHeight;
            }
        }
    } catch (error) {
        console.error('Failed to fetch log:', error);
    }
}
