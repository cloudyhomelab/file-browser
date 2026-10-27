package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.vaadin.filebrowser.data.ArchiveFormat;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.ExtractOptions;
import org.vaadin.filebrowser.storage.LocalDiskProvider;
import org.vaadin.filebrowser.storage.StorageException;

class ArchiveExtractorTest {

    private static final ArchiveExtractor.Limits ROOMY = new ArchiveExtractor.Limits(10 * 1024 * 1024, 100);

    @TempDir
    Path dir;

    private Path root;
    private LocalDiskProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(dir.resolve("root"));
        Files.createDirectory(root.resolve("target"));
        provider = new LocalDiskProvider(root, name -> "generic", type -> "disk");
    }

    /** Folder names end with "/"; everything else is a file with that content. */
    private static Map<String, String> sample() {
        final var entries = new LinkedHashMap<String, String>();
        entries.put("photos/", null);
        entries.put("photos/beach.jpg", "beach");
        entries.put("photos/2026/hill.jpg", "hill");
        entries.put("notes.txt", "notes");
        return entries;
    }

    private Path zip(Map<String, String> entries) throws IOException {
        final var file = dir.resolve("archive.zip");
        try (final var out = new ZipArchiveOutputStream(file)) {
            for (final var e : entries.entrySet()) {
                out.putArchiveEntry(new ZipArchiveEntry(e.getKey()));
                if (e.getValue() != null) {
                    out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                out.closeArchiveEntry();
            }
        }
        return file;
    }

    private Path tar(ArchiveFormat format, Map<String, String> entries) throws IOException {
        final var file = dir.resolve("archive." + format.name().toLowerCase());
        try (final var raw = Files.newOutputStream(file); final var compressed = compress(format, raw);
             final var out = new TarArchiveOutputStream(compressed)) {
            for (final var e : entries.entrySet()) {
                final var entry = new TarArchiveEntry(e.getKey());
                final var data = e.getValue() == null ? new byte[0] : e.getValue().getBytes(StandardCharsets.UTF_8);
                entry.setSize(data.length);
                out.putArchiveEntry(entry);
                out.write(data);
                out.closeArchiveEntry();
            }
        }
        return file;
    }

    private static OutputStream compress(ArchiveFormat format, OutputStream raw) throws IOException {
        return switch (format) {
            case TAR -> raw;
            case TAR_GZ, GZ -> new GzipCompressorOutputStream(raw);
            case TAR_BZ2, BZ2 -> new BZip2CompressorOutputStream(raw);
            case TAR_XZ, XZ -> new XZCompressorOutputStream(raw);
            case TAR_LZ, LZ -> new LzipOutputStream(raw);
            default -> throw new IllegalArgumentException(format.name());
        };
    }

    private Path sevenZ(Map<String, String> entries) throws IOException {
        final var file = dir.resolve("archive.7z");
        try (final var out = new SevenZOutputFile(file.toFile())) {
            for (final var e : entries.entrySet()) {
                final var entry = out.createArchiveEntry(dir.toFile(), e.getKey());
                entry.setDirectory(e.getValue() == null);
                out.putArchiveEntry(entry);
                if (e.getValue() != null) {
                    out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                out.closeArchiveEntry();
            }
        }
        return file;
    }

    private ArchiveExtractor.Result extract(Path archive, ArchiveFormat format, ExtractOptions.Clash clash,
                                           ArchiveExtractor.Limits limits) {
        return ArchiveExtractor.extract(archive, format, "single.txt", provider, "target", clash, limits, "archive");
    }

    private void assertSampleExtracted() throws IOException {
        assertThat(Files.readString(root.resolve("target/photos/beach.jpg"))).isEqualTo("beach");
        assertThat(Files.readString(root.resolve("target/photos/2026/hill.jpg"))).isEqualTo("hill");
        assertThat(Files.readString(root.resolve("target/notes.txt"))).isEqualTo("notes");
    }

    @Test
    void extractsZipsWithTheirFolders() throws IOException {
        final var result = extract(zip(sample()), ArchiveFormat.ZIP, ExtractOptions.Clash.KEEP_BOTH, ROOMY);
        assertSampleExtracted();
        assertThat(result).isEqualTo(new ArchiveExtractor.Result(3, 0));
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"TAR", "TAR_GZ", "TAR_BZ2", "TAR_XZ", "TAR_LZ"})
    void extractsTars(ArchiveFormat format) throws IOException {
        extract(tar(format, sample()), format, ExtractOptions.Clash.KEEP_BOTH, ROOMY);
        assertSampleExtracted();
    }

    @Test
    void extractsSevenZip() throws IOException {
        extract(sevenZ(sample()), ArchiveFormat.SEVEN_Z, ExtractOptions.Clash.KEEP_BOTH, ROOMY);
        assertSampleExtracted();
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"GZ", "BZ2", "XZ", "LZ"})
    void decompressesSingleFilesUnderTheirOwnName(ArchiveFormat format) throws IOException {
        final var file = dir.resolve("single." + format.name().toLowerCase());
        try (final var out = compress(format, Files.newOutputStream(file))) {
            out.write("just one file".getBytes(StandardCharsets.UTF_8));
        }
        extract(file, format, ExtractOptions.Clash.KEEP_BOTH, ROOMY);
        assertThat(Files.readString(root.resolve("target/single.txt"))).isEqualTo("just one file");
    }

    @Test
    void entriesThatWouldLeaveTheFolderAreSkipped() throws IOException {
        final var entries = new LinkedHashMap<String, String>();
        entries.put("../escaped.txt", "out");
        entries.put("photos/../../escaped-too.txt", "out");
        entries.put("/etc/abs.txt", "inside");
        entries.put("..\\windows.txt", "out");
        entries.put("ok.txt", "fine");
        final var result = extract(zip(entries), ArchiveFormat.ZIP, ExtractOptions.Clash.KEEP_BOTH, ROOMY);
        assertThat(dir.resolve("escaped.txt")).doesNotExist();
        assertThat(root.resolve("escaped.txt")).doesNotExist();
        assertThat(root.resolve("escaped-too.txt")).doesNotExist();
        assertThat(root.resolve("windows.txt")).doesNotExist();
        // an absolute path is taken as relative to the target folder
        assertThat(Files.readString(root.resolve("target/etc/abs.txt"))).isEqualTo("inside");
        assertThat(Files.readString(root.resolve("target/ok.txt"))).isEqualTo("fine");
        assertThat(result.skipped()).isEqualTo(3);
    }

    @Test
    void linksAndSpecialEntriesAreSkipped() throws IOException {
        final var file = dir.resolve("links.tar");
        try (final var out = new TarArchiveOutputStream(Files.newOutputStream(file))) {
            final var link = new TarArchiveEntry("passwd", TarConstants.LF_SYMLINK);
            link.setLinkName("/etc/passwd");
            out.putArchiveEntry(link);
            out.closeArchiveEntry();
            final var plain = new TarArchiveEntry("plain.txt");
            plain.setSize(5);
            out.putArchiveEntry(plain);
            out.write("plain".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        final var result = extract(file, ArchiveFormat.TAR, ExtractOptions.Clash.KEEP_BOTH, ROOMY);
        assertThat(root.resolve("target/passwd")).doesNotExist();
        assertThat(Files.readString(root.resolve("target/plain.txt"))).isEqualTo("plain");
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void anArchiveExpandingPastTheSizeLimitStopsAndLeavesNothing() throws IOException {
        final var entries = new LinkedHashMap<String, String>();
        entries.put("first/ok.txt", "small");
        entries.put("bomb.bin", "0".repeat(10 * 1024 * 1024));
        final var archive = zip(entries);
        assertThat(Files.size(archive)).isLessThan(100_000);
        final var tight = new ArchiveExtractor.Limits(1024 * 1024, 100);
        assertThatThrownBy(() -> extract(archive, ArchiveFormat.ZIP, ExtractOptions.Clash.KEEP_BOTH, tight))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("extraction limit");
        try (final var left = Files.list(root.resolve("target"))) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void tooManyEntriesStopAndLeaveNothing() throws IOException {
        final var entries = new LinkedHashMap<String, String>();
        for (int i = 0; i < 20; i++) {
            entries.put("many/file-" + i + ".txt", "x");
        }
        final var few = new ArchiveExtractor.Limits(1024 * 1024, 10);
        assertThatThrownBy(() -> extract(zip(entries), ArchiveFormat.ZIP, ExtractOptions.Clash.KEEP_BOTH, few))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("more than 10 entries");
        try (final var left = Files.list(root.resolve("target"))) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void clashesFollowThePolicy() throws IOException {
        final var entries = Map.of("a.txt", "new");
        Files.writeString(root.resolve("target/a.txt"), "old");

        extract(zip(entries), ArchiveFormat.ZIP, ExtractOptions.Clash.KEEP_BOTH, ROOMY);
        assertThat(Files.readString(root.resolve("target/a.txt"))).isEqualTo("old");
        assertThat(Files.readString(root.resolve("target/a (1).txt"))).isEqualTo("new");

        final var skipped = extract(zip(entries), ArchiveFormat.ZIP, ExtractOptions.Clash.SKIP, ROOMY);
        assertThat(skipped).isEqualTo(new ArchiveExtractor.Result(0, 1));

        extract(zip(entries), ArchiveFormat.ZIP, ExtractOptions.Clash.REPLACE, ROOMY);
        assertThat(Files.readString(root.resolve("target/a.txt"))).isEqualTo("new");
    }

    @Test
    void namesTellTheFormatLongestSuffixFirst() {
        final var catalog = new Catalog();
        assertThat(catalog.archiveFormat("photos.tar.gz")).contains(new ArchiveFormat.Match(ArchiveFormat.TAR_GZ, "photos"));
        assertThat(catalog.archiveFormat("notes.txt.GZ")).contains(new ArchiveFormat.Match(ArchiveFormat.GZ, "notes.txt"));
        assertThat(catalog.archiveFormat("texinfo-4.13.tar.lz")).contains(new ArchiveFormat.Match(ArchiveFormat.TAR_LZ, "texinfo-4.13"));
        assertThat(catalog.archiveFormat("release.7z")).contains(new ArchiveFormat.Match(ArchiveFormat.SEVEN_Z, "release"));
        assertThat(catalog.archiveFormat("report.pdf")).isEmpty();
        assertThat(catalog.archiveFormat(".zip")).isEmpty();
    }
}
