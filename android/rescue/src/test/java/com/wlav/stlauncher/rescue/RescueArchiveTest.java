package com.wlav.stlauncher.rescue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class RescueArchiveTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exportsImportCompatibleDataRootWithUnicodeAndEmptyDirectories() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Files.createDirectories(source.resolve("characters/空目录"));
        byte[] chat = "你好，SillyTavern".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source.resolve("characters/角色.json"), chat);
        writeText(source.resolve("settings.json"), "{\"theme\":\"dark\"}");

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        RescueArchive.ExportResult result = RescueArchive.export(source, destination, null);
        Map<String, byte[]> archive = readArchive(destination.toByteArray());

        assertEquals(2, result.fileCount());
        assertEquals(2, result.directoryCount());
        assertTrue(archive.containsKey("data/"));
        assertTrue(archive.containsKey("data/characters/"));
        assertTrue(archive.containsKey("data/characters/空目录/"));
        assertArrayEquals(chat, archive.get("data/characters/角色.json"));
        assertEquals(
                "{\"theme\":\"dark\"}",
                new String(
                        archive.get("data/settings.json"),
                        java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void omitsOnlyTransientDirectoriesAtDataRoot() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Files.createDirectories(source.resolve("temp_uploads/nested"));
        writeText(source.resolve("temp_uploads/ignored.txt"), "ignore");
        Files.createDirectories(source.resolve("nested/temp_uploads"));
        writeText(source.resolve("nested/temp_uploads/kept.txt"), "keep");
        Files.createDirectories(source.resolve("_webpack"));
        writeText(source.resolve("_webpack/cache"), "ignore");

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        RescueArchive.export(source, destination, null);
        Map<String, byte[]> archive = readArchive(destination.toByteArray());

        assertFalse(archive.keySet().stream().anyMatch(name -> name.startsWith("data/_webpack")));
        assertFalse(archive.keySet().stream().anyMatch(name -> name.startsWith("data/temp_uploads")));
        assertEquals(
                "keep",
                new String(
                        archive.get("data/nested/temp_uploads/kept.txt"),
                        java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void reportsScanCountsAndBytesWithoutChangingSource() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Files.createDirectories(source.resolve("one/two"));
        Files.write(source.resolve("one/a.bin"), new byte[] {1, 2, 3});
        Files.write(source.resolve("b.bin"), new byte[] {4, 5});

        RescueArchive.ScanResult result = RescueArchive.scan(source);

        assertEquals(2, result.fileCount());
        assertEquals(2, result.directoryCount());
        assertEquals(5, result.bytes());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(source.resolve("one/a.bin")));
    }

    @Test
    void rejectsSymbolicLinks() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path outside = writeText(temporaryDirectory.resolve("outside"), "secret");
        try {
            Files.createSymbolicLink(source.resolve("link"), outside);
        } catch (UnsupportedOperationException error) {
            return;
        }

        assertThrows(IOException.class, () -> RescueArchive.scan(source));
    }

    private static Path writeText(Path path, String content) throws IOException {
        return Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> readArchive(byte[] compressed) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (TarArchiveInputStream input =
                new TarArchiveInputStream(
                        new GzipCompressorInputStream(new ByteArrayInputStream(compressed)))) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), entry.isDirectory() ? new byte[0] : input.readAllBytes());
            }
        }
        return entries;
    }
}
