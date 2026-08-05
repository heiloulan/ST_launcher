export class LauncherError extends Error {
    constructor(code, message, status = 500, retryable = false, cause) {
        super(message, { cause });
        this.name = 'LauncherError';
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }
}

export function sendError(response, error) {
    const known = error instanceof LauncherError
        ? error
        : new LauncherError('INTERNAL_ERROR', error?.message || 'Operation failed');
    console.error(`[ST Launcher] ${known.code}:`, error);
    if (response.headersSent) {
        response.destroy(error);
        return;
    }
    response.status(known.status).json({
        error: {
            code: known.code,
            message: known.message,
            retryable: known.retryable,
        },
    });
}
