package com.wlav.stlauncher.rescue;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Creates an import-compatible, data/-rooted tar.gz without changing its source. */
public final class RescueArchive {
    private static final int MAXIMUM_ENTRIES = 200_000;
    private static final int MAXIMUM_PATH_LENGTH = 512;
    private static final Set<String> TRANSIENT_ROOT_ENTRIES =
            Set.of("_webpack", "temp_uploads");

    private RescueArchive() {}

    public interface ProgressListener {
        void onProgress(long completedBytes, long totalBytes, int completedFiles, int totalFiles);
    }

    public record ScanResult(int fileCount, int directoryCount, long bytes) {}

    public record ExportResult(int fileCount, int directoryCount, long sourceBytes, long archiveBytes) {}

    private record SourceEntry(Path source, String relative, BasicFileAttributes attributes) {}

    private record Snapshot(List<SourceEntry> entries, ScanResult result) {}

    public static ScanResult scan(Path sourceRoot) throws IOException {
        return snapshot(sourceRoot).result();
    }

    public static ExportResult export(
            Path sourceRoot,
            OutputStream destination,
            ProgressListener progressListener
    ) throws IOException {
        Snapshot snapshot = snapshot(sourceRoot);
        ScanResult scan = snapshot.result();
        CountingOutputStream counted = new CountingOutputStream(destination);

        try (TarArchiveOutputStream archive =
                new TarArchiveOutputStream(
                        new GzipCompressorOutputStream(
                                new BufferedOutputStream(counted, 128 * 1024)))) {
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            archive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            archive.setAddPaxHeadersForNonAsciiNames(true);

            writeDirectory(archive, "data/", 0L);
            long completedBytes = 0L;
            int completedFiles = 0;
            for (SourceEntry entry : snapshot.entries()) {
                throwIfInterrupted();
                String archiveName = "data/" + entry.relative();
                if (entry.attributes().isDirectory()) {
                    writeDirectory(archive, archiveName + "/", entry.attributes().lastModifiedTime().toMillis());
                    continue;
                }

                writeStableFile(archive, entry, archiveName);
                completedBytes += entry.attributes().size();
                completedFiles++;
                if (progressListener != null) {
                    progressListener.onProgress(
                            completedBytes,
                            scan.bytes(),
                            completedFiles,
                            scan.fileCount());
                }
            }
            archive.finish();
        }

        if (counted.count() <= 0) {
            throw new IOException("生成的备份文件为空");
        }
        return new ExportResult(
                scan.fileCount(), scan.directoryCount(), scan.bytes(), counted.count());
    }

    private static Snapshot snapshot(Path sourceRoot) throws IOException {
        BasicFileAttributes rootAttributes = readAttributes(sourceRoot);
        if (!rootAttributes.isDirectory()) {
            throw new IOException("数据目录不存在");
        }

        List<SourceEntry> entries = new ArrayList<>();
        collect(sourceRoot, sourceRoot, entries);
        int fileCount = 0;
        int directoryCount = 0;
        long bytes = 0L;
        for (SourceEntry entry : entries) {
            if (entry.attributes().isDirectory()) {
                directoryCount++;
            } else {
                fileCount++;
                bytes = Math.addExact(bytes, entry.attributes().size());
            }
        }
        return new Snapshot(entries, new ScanResult(fileCount, directoryCount, bytes));
    }

    private static void collect(Path root, Path directory, List<SourceEntry> entries)
            throws IOException {
        throwIfInterrupted();
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));

        for (Path child : children) {
            boolean isRootEntry = directory.equals(root);
            String fileName = child.getFileName().toString();
            if (isRootEntry && TRANSIENT_ROOT_ENTRIES.contains(fileName)) {
                continue;
            }

            BasicFileAttributes attributes = readAttributes(child);
            String relative = portableRelativePath(root, child);
            validateArchivePath("data/" + relative);
            if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                throw new IOException("用户数据包含不支持的文件类型：" + relative);
            }
            entries.add(new SourceEntry(child, relative, attributes));
            if (entries.size() > MAXIMUM_ENTRIES - 1) {
                throw new IOException("用户数据条目超过 200,000 个，无法生成兼容备份");
            }
            if (attributes.isDirectory()) {
                collect(root, child, entries);
            }
        }
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("用户数据包含符号链接或特殊文件：" + path.getFileName());
        }
        return attributes;
    }

    private static String portableRelativePath(Path root, Path child) {
        Path relative = root.relativize(child);
        StringBuilder result = new StringBuilder();
        for (Path part : relative) {
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(part.toString());
        }
        return result.toString();
    }

    private static void validateArchivePath(String path) throws IOException {
        if (path.length() > MAXIMUM_PATH_LENGTH
                || path.indexOf('\\') >= 0
                || path.startsWith("/")
                || path.contains("/../")
                || path.endsWith("/..")) {
            throw new IOException("用户数据路径无法安全导出：" + path);
        }
    }

    private static void writeDirectory(TarArchiveOutputStream archive, String name, long modified)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setMode(0700);
        entry.setSize(0L);
        if (modified > 0) {
            entry.setModTime(modified);
        }
        archive.putArchiveEntry(entry);
        archive.closeArchiveEntry();
    }

    private static void writeStableFile(
            TarArchiveOutputStream archive,
            SourceEntry sourceEntry,
            String archiveName
    ) throws IOException {
        BasicFileAttributes before = readAttributes(sourceEntry.source());
        if (!before.isRegularFile()
                || before.size() != sourceEntry.attributes().size()
                || !before.lastModifiedTime().equals(sourceEntry.attributes().lastModifiedTime())) {
            throw new IOException("导出期间文件发生变化，请重试：" + sourceEntry.relative());
        }

        TarArchiveEntry tarEntry = new TarArchiveEntry(archiveName);
        tarEntry.setMode(0600);
        tarEntry.setSize(before.size());
        tarEntry.setModTime(before.lastModifiedTime().toMillis());
        archive.putArchiveEntry(tarEntry);

        long remaining = before.size();
        Set<OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.READ);
        options.add(LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(sourceEntry.source(), options);
                InputStream input = Channels.newInputStream(channel)) {
            byte[] buffer = new byte[128 * 1024];
            while (remaining > 0) {
                throwIfInterrupted();
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("导出期间文件被截断：" + sourceEntry.relative());
                }
                archive.write(buffer, 0, read);
                remaining -= read;
            }
            if (input.read() != -1) {
                throw new IOException("导出期间文件大小发生变化，请重试：" + sourceEntry.relative());
            }
        }
        archive.closeArchiveEntry();

        BasicFileAttributes after = readAttributes(sourceEntry.source());
        if (!after.isRegularFile()
                || after.size() != before.size()
                || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
            throw new IOException("导出期间文件发生变化，请重试：" + sourceEntry.relative());
        }
    }

    private static void throwIfInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("导出已取消");
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
        }

        private long count() {
            return count;
        }
    }
}
