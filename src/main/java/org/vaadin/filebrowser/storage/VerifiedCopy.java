package org.vaadin.filebrowser.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A copy this app streams from one provider to another. Each file is written to a temporary
 * {@code <uuid>.partial} next to its destination, read back, and put in place only when its SHA-256
 * matches the source's. A mismatch, failure or cancel removes everything written so far.
 */
public final class VerifiedCopy {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String HASH = "SHA-256";
    private static final String TEMPORARY_SUFFIX = ".partial";

    private VerifiedCopy() {
    }

    /**
     * What a copy wrote, for deleting the originals of a move.
     *
     * @param files   the source files that were copied and verified, as they were listed before being read
     * @param folders the source folders that were copied, parents before their contents
     */
    public record Result(List<StorageEntry> files, List<String> folders) {
    }

    /**
     * Copies {@code path} of {@code from} into {@code folder} of {@code to} as {@code name}. With {@code replace},
     * an existing file of that name is replaced once the copy is verified; a folder is never replaced.
     */
    public static Result copy(StorageProvider from, String path, StorageProvider to, String folder, String name,
                              boolean replace, CopyProgress progress) {
        StoragePaths.requireValidName(name);
        final var source = from.stat(path)
                .orElseThrow(() -> new StorageException("“" + nameOf(path) + "” no longer exists."));
        final var target = StoragePaths.child(folder, name);
        final var existing = to.stat(target);
        if (existing.isPresent() && (!replace || source.folder() || existing.orElseThrow().folder())) {
            throw new StorageException(replace ? "“" + name + "” can't be replaced: only a file replaces a file."
                    : "“" + name + "” already exists in the destination.");
        }
        final var result = new Result(new ArrayList<>(), new ArrayList<>());
        if (source.folder()) {
            copyFolder(from, path, to, folder, name, progress, result);
        } else {
            copyFile(from, path, to, folder, name, existing.isPresent(), progress);
            result.files().add(source);
        }
        return new Result(List.copyOf(result.files()), List.copyOf(result.folders()));
    }

    private static void copyFolder(StorageProvider from, String path, StorageProvider to, String folder, String name,
                                   CopyProgress progress, Result result) {
        to.createFolder(folder, name);
        result.folders().add(path);
        final var target = StoragePaths.child(folder, name);
        try {
            for (final var child : from.list(path)) {
                if (child.folder()) {
                    copyFolder(from, child.path(), to, target, child.name(), progress, result);
                } else {
                    copyFile(from, child.path(), to, target, child.name(), false, progress);
                    result.files().add(child);
                }
            }
        } catch (RuntimeException e) {
            deleteQuietly(to, target);
            throw e;
        }
    }

    private static void copyFile(StorageProvider from, String path, StorageProvider to, String folder, String name,
                                 boolean replace, CopyProgress progress) {
        final var temporary = StoragePaths.child(folder, UUID.randomUUID() + TEMPORARY_SUFFIX);
        final var temporaryName = nameOf(temporary);
        try {
            final byte[] written;
            progress.phase(name, CopyProgress.Phase.COPYING);
            try (var in = from.openRead(path);
                 var write = to.openWrite(folder, temporaryName, false)) {
                written = transfer(in, write.getStream(), name, progress::copied, progress);
                write.commit();
            }
            final byte[] readBack;
            progress.phase(name, CopyProgress.Phase.VERIFYING);
            try (var in = to.openRead(temporary)) {
                readBack = transfer(in, OutputStream.nullOutputStream(), name, progress::verified, progress);
            }
            if (!Arrays.equals(written, readBack)) {
                throw new StorageException("The copy of “" + name + "” didn't match the original.");
            }
            if (replace) {
                to.renameReplacing(temporary, name);
            } else {
                to.rename(temporary, name);
            }
        } catch (IOException e) {
            deleteQuietly(to, temporary);
            throw new StorageException("Could not copy “" + name + "”.", e);
        } catch (RuntimeException e) {
            deleteQuietly(to, temporary);
            throw e;
        }
    }

    @FunctionalInterface
    private interface Counter {
        void add(long bytes);
    }

    /** Streams {@code in} to {@code out} and returns the SHA-256 of what went through. */
    private static byte[] transfer(InputStream in, OutputStream out, String name, Counter counter,
                                   CopyProgress progress) throws IOException {
        final var digest = sha256();
        final var buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (progress.cancelled()) {
                throw new CopyCancelledException(name);
            }
            out.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            counter.add(read);
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(HASH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides " + HASH, e);
        }
    }

    private static void deleteQuietly(StorageProvider provider, String path) {
        try {
            if (provider.stat(path).isPresent()) {
                provider.delete(path);
            }
        } catch (StorageException e) {
            // the copy's own failure is what gets reported
        }
    }

    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
