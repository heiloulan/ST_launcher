package com.wlav.stlauncher.rescue;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RescueActivity extends ComponentActivity {
    private static final int CREATE_BACKUP_REQUEST = 1001;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Button currentButton;
    private Button legacyButton;
    private TextView currentStatus;
    private TextView legacyStatus;
    private TextView exportStatus;
    private ProgressBar progress;
    private DataSource pendingSource;
    private boolean exporting;
    private boolean currentAvailable;
    private boolean legacyAvailable;

    private enum DataSource {
        CURRENT("current"),
        LEGACY("legacy");

        private final String fileLabel;

        DataSource(String fileLabel) {
            this.fileLabel = fileLabel;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rescue);

        currentButton = findViewById(R.id.export_current);
        legacyButton = findViewById(R.id.export_legacy);
        currentStatus = findViewById(R.id.current_status);
        legacyStatus = findViewById(R.id.legacy_status);
        exportStatus = findViewById(R.id.export_status);
        progress = findViewById(R.id.progress);

        currentButton.setOnClickListener(view -> chooseDestination(DataSource.CURRENT));
        legacyButton.setOnClickListener(view -> chooseDestination(DataSource.LEGACY));
        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (exporting) {
                            Toast.makeText(
                                    RescueActivity.this,
                                    R.string.export_busy,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
        scanSources();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            worker.shutdownNow();
        }
        super.onDestroy();
    }

    private Path sourcePath(DataSource source) {
        Path filesRoot = getFilesDir().toPath();
        if (source == DataSource.CURRENT) {
            return filesRoot.resolve("user-data");
        }
        return filesRoot.resolve("sillytavern/SillyTavern/data");
    }

    private void scanSources() {
        scanSource(DataSource.CURRENT, currentStatus);
        scanSource(DataSource.LEGACY, legacyStatus);
    }

    private void scanSource(DataSource source, TextView statusView) {
        statusView.setText(R.string.checking);
        worker.execute(() -> {
            try {
                RescueArchive.ScanResult result = RescueArchive.scan(sourcePath(source));
                main.post(() -> {
                    setAvailable(source, true);
                    statusView.setText(
                            getString(R.string.found, result.fileCount(), formatBytes(result.bytes())));
                    statusView.setTextColor(getColor(R.color.text_primary));
                    updateButtons();
                });
            } catch (IOException error) {
                main.post(() -> {
                    setAvailable(source, false);
                    if (!java.nio.file.Files.exists(sourcePath(source))) {
                        statusView.setText(R.string.not_found);
                    } else {
                        statusView.setText(
                                getString(R.string.scan_failed, safeMessage(error)));
                    }
                    statusView.setTextColor(getColor(R.color.text_secondary));
                    updateButtons();
                });
            }
        });
    }

    private void setAvailable(DataSource source, boolean available) {
        if (source == DataSource.CURRENT) {
            currentAvailable = available;
        } else {
            legacyAvailable = available;
        }
    }

    private void updateButtons() {
        currentButton.setEnabled(currentAvailable && !exporting && pendingSource == null);
        legacyButton.setEnabled(legacyAvailable && !exporting && pendingSource == null);
    }

    private void chooseDestination(DataSource source) {
        if (exporting || pendingSource != null) {
            return;
        }
        pendingSource = source;
        updateButtons();

        String timestamp =
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        String fileName =
                "sillytavern_backup_rescue_"
                        + source.fileLabel
                        + "_"
                        + timestamp
                        + ".tar.gz";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/gzip")
                .putExtra(Intent.EXTRA_TITLE, fileName);
        Toast.makeText(this, R.string.choose_destination, Toast.LENGTH_SHORT).show();
        try {
            startActivityForResult(intent, CREATE_BACKUP_REQUEST);
        } catch (RuntimeException error) {
            pendingSource = null;
            updateButtons();
            showFailure(error);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_BACKUP_REQUEST) {
            return;
        }
        DataSource source = pendingSource;
        pendingSource = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || source == null) {
            updateButtons();
            return;
        }
        export(source, data.getData());
    }

    private void export(DataSource source, Uri destination) {
        exporting = true;
        updateButtons();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        exportStatus.setVisibility(View.VISIBLE);
        exportStatus.setText(R.string.exporting_preparing);
        exportStatus.setTextColor(getColor(R.color.text_primary));

        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IOException("无法打开所选保存位置");
                }
                final long[] lastUpdate = {0L};
                RescueArchive.ExportResult result =
                        RescueArchive.export(
                                sourcePath(source),
                                output,
                                (completedBytes, totalBytes, completedFiles, totalFiles) -> {
                                    long now = System.nanoTime();
                                    if (completedFiles != totalFiles
                                            && now - lastUpdate[0] < 100_000_000L) {
                                        return;
                                    }
                                    lastUpdate[0] = now;
                                    main.post(() -> showProgress(
                                            completedBytes,
                                            totalBytes,
                                            completedFiles,
                                            totalFiles));
                                });
                main.post(() -> finishExport(result));
            } catch (Throwable error) {
                deleteFailedDocument(destination);
                main.post(() -> showFailure(error));
            }
        });
    }

    private void showProgress(
            long completedBytes,
            long totalBytes,
            int completedFiles,
            int totalFiles
    ) {
        progress.setIndeterminate(false);
        int value;
        if (totalBytes > 0) {
            value = (int) Math.min(1000L, completedBytes * 1000L / totalBytes);
        } else if (totalFiles > 0) {
            value = Math.min(1000, completedFiles * 1000 / totalFiles);
        } else {
            value = 1000;
        }
        progress.setProgress(value);
        exportStatus.setText(
                getString(
                        R.string.exporting,
                        completedFiles,
                        totalFiles,
                        formatBytes(completedBytes),
                        formatBytes(totalBytes)));
    }

    private void finishExport(RescueArchive.ExportResult result) {
        exporting = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setIndeterminate(false);
        progress.setProgress(1000);
        exportStatus.setText(
                getString(
                        R.string.export_succeeded,
                        result.fileCount(),
                        formatBytes(result.archiveBytes())));
        exportStatus.setTextColor(Color.rgb(143, 227, 208));
        updateButtons();
        Toast.makeText(
                this,
                getString(
                        R.string.export_succeeded,
                        result.fileCount(),
                        formatBytes(result.archiveBytes())),
                Toast.LENGTH_LONG).show();
    }

    private void showFailure(Throwable error) {
        exporting = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setVisibility(View.GONE);
        exportStatus.setVisibility(View.VISIBLE);
        exportStatus.setText(getString(R.string.export_failed, safeMessage(error)));
        exportStatus.setTextColor(getColor(R.color.warning));
        updateButtons();
        Toast.makeText(
                this,
                getString(R.string.export_failed, safeMessage(error)),
                Toast.LENGTH_LONG).show();
    }

    private void deleteFailedDocument(Uri destination) {
        try {
            if ("content".equals(destination.getScheme())) {
                DocumentsContract.deleteDocument(getContentResolver(), destination);
            }
        } catch (Exception ignored) {
            // Some providers do not support deletion. The UI still reports the failed export.
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
