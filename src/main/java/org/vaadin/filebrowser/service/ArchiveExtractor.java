package org.vaadin.filebrowser.service;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.vaadin.filebrowser.data.ArchiveFormat;
import org.vaadin.filebrowser.data.ExtractOptions;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;

/**
 * Unpacks an uploaded archive into a folder of a file system, through its {@link StorageProvider}
 * so disks and buckets behave the same. Entries are untrusted: a path that would leave the folder,
 * a link or a special file is skipped, and the size and number of entries are limited by what is
 * actually read, not by what the archive claims. When extraction fails, whatever it wrote is removed.
 */
public final class ArchiveExtractor {

    /** Upper bounds for one archive. */
    public record Limits(long maxBytes, int maxEntries) {
    }

    public record Result(int written, int skipped) {
    }

    private static final int BUFFER_SIZE = 64 * 1024;
    /** Tar entry types that hold a plain file. */
    private static final Set<Byte> REGULAR_TAR_TYPES = Set.of(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM,
            TarConstants.LF_CONTIG);

    /** One entry as read from any of the formats. */
    private interface Entry {

        String name();

        boolean directory();

        /** Regular files and folders; links, devices and the like are not. */
        boolean regular();

        InputStream open() throws IOException;
    }

    @FunctionalInterface
    private interface EntryConsumer {

        void accept(Entry entry) throws IOException;
    }

    private final StorageProvider provider;
    private final String target;
    private final ExtractOptions.Clash clash;
    private final Limits limits;
    private final List<String> createdFolders = new ArrayList<>();
    private final List<String> writtenFiles = new ArrayList<>();
    private final Set<String> knownFolders = new HashSet<>();
    private final Map<String, Set<String>> namesIn = new HashMap<>();
    private long bytes;
    private int entries;
    private int written;
    private int skipped;

    private ArchiveExtractor(StorageProvider provider, String target, ExtractOptions.Clash clash, Limits limits) {
        this.provider = provider;
        this.target = target;
        this.clash = clash;
        this.limits = limits;
        knownFolders.add(target);
    }

    /**
     * @param archive    the archive, already on local disk
     * @param singleName the file name for a single compressed file ({@code notes.txt} for {@code notes.txt.gz})
     * @param target     an existing folder of the file system to extract into
     * @param label      the archive's name, for messages
     */
    public static Result extract(Path archive, ArchiveFormat format, String singleName, StorageProvider provider,
                                 String target, ExtractOptions.Clash clash, Limits limits, String label) {
        final var extractor = new ArchiveExtractor(provider, target, clash, limits);
        try {
            extractor.read(archive, format, singleName);
            return new Result(extractor.written, extractor.skipped);
        } catch (LimitException e) {
            extractor.cleanUp();
            throw new StorageException("“" + label + "” " + e.getMessage(), e);
        } catch (StorageException e) {
            extractor.cleanUp();
            throw e;
        } catch (IOException | RuntimeException e) {
            extractor.cleanUp();
            throw new StorageException("“" + label + "” couldn't be extracted.", e);
        }
    }

    // ---- reading the formats ----------------------------------------------------------------

    private void read(Path archive, ArchiveFormat format, String singleName) throws IOException {
        switch (format) {
            case ZIP -> readZip(archive);
            case SEVEN_Z -> readSevenZ(archive);
            case TAR, TAR_GZ, TAR_BZ2, TAR_XZ -> {
                try (final var tar = new TarArchiveInputStream(decompress(archive, format))) {
                    readTar(tar);
                }
            }
            case GZ, BZ2, XZ -> {
                try (final var in = decompress(archive, format)) {
                    accept(new Entry() {
                        public String name() { return singleName; }
                        public boolean directory() { return false; }
                        public boolean regular() { return true; }
                        public InputStream open() { return new Unclosable(in); }
                    });
                }
            }
        }
    }

    private static InputStream decompress(Path archive, ArchiveFormat format) throws IOException {
        final var raw = new BufferedInputStream(Files.newInputStream(archive));
        return switch (format) {
            case TAR -> raw;
            case TAR_GZ, GZ -> GzipCompressorInputStream.builder().setInputStream(raw).setDecompressConcatenated(true).get();
            case TAR_BZ2, BZ2 -> new BZip2CompressorInputStream(raw, true);
            case TAR_XZ, XZ -> new XZCompressorInputStream(raw, true);
            default -> throw new IllegalArgumentException(format.name());
        };
    }

    private void readZip(Path archive) throws IOException {
        try (final var zip = ZipFile.builder().setPath(archive).get()) {
            final var all = zip.getEntries();
            while (all.hasMoreElements()) {
                final var entry = all.nextElement();
                accept(new Entry() {
                    public String name() { return entry.getName(); }
                    public boolean directory() { return entry.isDirectory(); }
                    public boolean regular() { return !entry.isUnixSymlink(); }
                    public InputStream open() throws IOException { return zip.getInputStream(entry); }
                });
            }
        }
    }

    private void readTar(TarArchiveInputStream tar) throws IOException {
        for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
            final var current = entry;
            accept(new Entry() {
                public String name() { return current.getName(); }
                public boolean directory() { return current.isDirectory(); }
                // isFile() also says yes to links and devices, so the entry type is checked directly
                public boolean regular() { return current.isDirectory() || REGULAR_TAR_TYPES.contains(current.getLinkFlag()); }
                public InputStream open() { return new Unclosable(tar); }
            });
        }
    }

    private void readSevenZ(Path archive) throws IOException {
        try (final var sevenZ = SevenZFile.builder().setPath(archive).get()) {
            for (var entry = sevenZ.getNextEntry(); entry != null; entry = sevenZ.getNextEntry()) {
                final var current = entry;
                accept(new Entry() {
                    public String name() { return current.getName(); }
                    public boolean directory() { return current.isDirectory(); }
                    public boolean regular() { return !current.isAntiItem(); }
                    public InputStream open() throws IOException { return sevenZ.getInputStream(current); }
                });
            }
        }
    }

    // ---- writing ------------------------------------------------------------------------------

    private void accept(Entry entry) throws IOException {
        if (++entries > limits.maxEntries()) {
            throw new LimitException("has more than " + limits.maxEntries() + " entries.");
        }
        final var segments = segments(entry.name());
        if (segments == null || !entry.regular()) {
            skipped++;
            return;
        }
        if (entry.directory()) {
            ensureFolder(segments);
            return;
        }
        final var parent = ensureFolder(segments.subList(0, segments.size() - 1));
        if (parent == null) {
            skipped++;
            return;
        }
        writeFile(parent, segments.getLast(), entry);
    }

    /** The entry's path as safe segments, or null when it can't be placed inside the target folder. */
    static List<String> segments(String name) {
        if (name == null) {
            return null;
        }
        final var out = new ArrayList<String>();
        for (final var part : name.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            try {
                StoragePaths.requireValidName(part);
            } catch (StorageException e) {
                return null;
            }
            out.add(part);
        }
        return out.isEmpty() ? null : out;
    }

    /** Creates the folders on the way down as needed; null when a file is in the way. */
    private String ensureFolder(List<String> segments) {
        var path = target;
        for (final var segment : segments) {
            final var child = StoragePaths.child(path, segment);
            if (!knownFolders.contains(child)) {
                final var existing = provider.stat(child);
                if (existing.isPresent() && !existing.get().folder()) {
                    return null;
                }
                if (existing.isEmpty()) {
                    provider.createFolder(path, segment);
                    createdFolders.add(child);
                    names(path).add(segment);
                }
                knownFolders.add(child);
            }
            path = child;
        }
        return path;
    }

    private Set<String> names(String folder) {
        return namesIn.computeIfAbsent(folder, f -> {
            final var names = new HashSet<String>();
            provider.list(f).stream().map(StorageEntry::name).forEach(names::add);
            return names;
        });
    }

    private void writeFile(String folder, String name, Entry entry) throws IOException {
        final var names = names(folder);
        var finalName = name;
        var replace = false;
        if (names.contains(name)) {
            final var existing = provider.stat(StoragePaths.child(folder, name));
            if (existing.isPresent() && existing.get().folder() || clash == ExtractOptions.Clash.SKIP) {
                skipped++;
                return;
            }
            if (clash == ExtractOptions.Clash.REPLACE) {
                replace = true;
            } else {
                finalName = Workspace.keepBothName(name, names);
            }
        }
        final var path = StoragePaths.child(folder, finalName);
        try (final var write = provider.openWrite(folder, finalName, replace); final var in = entry.open()) {
            final var buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes += read;
                if (bytes > limits.maxBytes()) {
                    throw new LimitException("expands to more than the extraction limit.");
                }
                write.stream().write(buffer, 0, read);
            }
            write.commit();
        }
        if (!replace) {
            writtenFiles.add(path);
        }
        names.add(finalName);
        written++;
    }

    /** Removes the files and folders this extraction created; files it replaced keep their new content. */
    private void cleanUp() {
        for (final var file : writtenFiles) {
            try {
                provider.delete(file);
            } catch (StorageException e) {
                // best effort; the error being reported matters more
            }
        }
        for (final var folder : createdFolders.reversed()) {
            try {
                provider.delete(folder);
            } catch (StorageException e) {
                // as above
            }
        }
    }

    /** The archive went over one of the limits. */
    private static final class LimitException extends IOException {

        LimitException(String message) {
            super(message);
        }
    }

    /** Keeps an entry's reader from closing the stream that holds the archive's next entries. */
    static final class Unclosable extends FilterInputStream {

        Unclosable(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
        }
    }
}
