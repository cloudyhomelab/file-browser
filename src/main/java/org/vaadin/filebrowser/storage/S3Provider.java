package org.vaadin.filebrowser.storage;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.vaadin.filebrowser.data.Capacity;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import lombok.Getter;

/**
 * An S3 bucket, or a folder ("prefix") inside one, as a file system. Folders are key prefixes; an
 * empty folder exists as a zero-byte {@code name/} marker. Moves and renames copy then delete, so
 * they are not atomic.
 */
public class S3Provider implements StorageProvider, AutoCloseable {

    /** Parts of a multipart upload; S3 needs at least 5 MB for every part but the last. */
    private static final int PART_BYTES = 8 * 1024 * 1024;
    /** The most keys one DeleteObjects request takes. */
    private static final int DELETE_BATCH = 1000;
    private static final String SCHEME = "s3://";
    private static final int NOT_FOUND = 404;
    private static final int FORBIDDEN = 403;

    /** Where a file system lives: {@code s3://bucket} or {@code s3://bucket/some/prefix}. */
    public record Location(String bucket, String prefix) {

        /** @return null when {@code endpoint} isn't of that form */
        public static Location parse(String endpoint) {
            if (endpoint == null || !endpoint.startsWith(SCHEME)) {
                return null;
            }
            final var rest = endpoint.substring(SCHEME.length());
            final var slash = rest.indexOf('/');
            final var bucket = slash < 0 ? rest : rest.substring(0, slash);
            final var path = slash < 0 ? "" : rest.substring(slash + 1).replaceAll("/+$", "");
            if (bucket.isBlank() || path.contains("//")) {
                return null;
            }
            return new Location(bucket, path.isEmpty() ? "" : path + "/");
        }
    }

    /**
     * How to reach it. Both keys null means anonymous access (a public bucket); the server's own
     * AWS credentials are never used.
     *
     * @param endpointUrl null for AWS; set for S3-compatible stores, which then get path-style requests
     */
    public record Connection(String endpoint, String region, URI endpointUrl, String accessKey, String secretKey) {
    }

    private final Location location;
    private final S3Client client;
    private final Function<String, String> typeForName;

    public S3Provider(Connection connection, Function<String, String> typeForName) {
        this.location = Location.parse(connection.endpoint());
        if (location == null) {
            throw new StorageException("Use the form s3://bucket or s3://bucket/folder.");
        }
        this.typeForName = typeForName;
        final AwsCredentialsProvider credentials = connection.accessKey() == null
                ? AnonymousCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(connection.accessKey(), connection.secretKey()));
        final var builder = S3Client.builder()
                .region(Region.of(connection.region()))
                .credentialsProvider(credentials);
        if (connection.endpointUrl() != null) {
            // S3-compatible stores: bucket in the path, and only the checksums S3 itself requires
            builder.endpointOverride(connection.endpointUrl())
                    .forcePathStyle(Boolean.TRUE)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
        }
        this.client = builder.build();
    }

    // ---- keys -----------------------------------------------------------------------------

    private String key(String path) {
        StoragePaths.requireValidPath(path);
        return location.prefix() + path;
    }

    /** The prefix of everything inside a folder; the file system's prefix for its root. */
    private String folderKey(String path) {
        return path.isEmpty() ? location.prefix() : key(path) + "/";
    }

    private String relative(String key) {
        final var path = key.substring(location.prefix().length());
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String nameOf(String path) {
        final var slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String parentOf(String path) {
        final var slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    // ---- reading --------------------------------------------------------------------------

    @Override
    public List<StorageEntry> list(String folder) {
        requireFolder(folder);
        final var prefix = folderKey(folder);
        final var out = new ArrayList<StorageEntry>();
        try {
            final var pages = client.listObjectsV2Paginator(r -> r.bucket(location.bucket()).prefix(prefix).delimiter("/"));
            for (final var page : pages) {
                for (final var common : page.commonPrefixes()) {
                    final var path = relative(common.prefix());
                    out.add(folderEntry(path));
                }
                for (final var object : page.contents()) {
                    if (object.key().equals(prefix) || object.key().endsWith("/")) {
                        continue;
                    }
                    out.add(fileEntry(object));
                }
            }
        } catch (SdkException e) {
            throw failure("read this folder", e);
        }
        return out;
    }

    private StorageEntry folderEntry(String path) {
        return new StorageEntry(path, nameOf(path), true, StorageEntry.FOLDER_TYPE, null, null, null, null);
    }

    private StorageEntry fileEntry(S3Object object) {
        final var path = relative(object.key());
        final var name = nameOf(path);
        return new StorageEntry(path, name, false, typeForName.apply(name), object.size(), object.lastModified(), null,
                null);
    }

    @Override
    public Optional<StorageEntry> stat(String path) {
        if (path.isEmpty()) {
            return Optional.of(folderEntry(""));
        }
        final var file = headFile(path);
        if (file.isPresent()) {
            return file;
        }
        return folderExists(path) ? Optional.of(folderEntry(path)) : Optional.empty();
    }

    private Optional<StorageEntry> headFile(String path) {
        try {
            final var head = client.headObject(r -> r.bucket(location.bucket()).key(key(path)));
            final var name = nameOf(path);
            return Optional.of(new StorageEntry(path, name, false, typeForName.apply(name), head.contentLength(),
                    head.lastModified(), null, null));
        } catch (S3Exception e) {
            if (e.statusCode() == NOT_FOUND) {
                return Optional.empty();
            }
            throw failure("read “" + nameOf(path) + "”", e);
        } catch (SdkException e) {
            throw failure("read “" + nameOf(path) + "”", e);
        }
    }

    private boolean folderExists(String path) {
        if (path.isEmpty()) {
            return true;
        }
        try {
            return client.listObjectsV2(r -> r.bucket(location.bucket()).prefix(folderKey(path)).maxKeys(1)).keyCount() > 0;
        } catch (SdkException e) {
            throw failure("read “" + nameOf(path) + "”", e);
        }
    }

    private void requireFolder(String path) {
        if (!folderExists(path)) {
            throw new StorageException("“" + nameOf(path) + "” no longer exists.");
        }
    }

    private boolean exists(String path) {
        return headFile(path).isPresent() || folderExists(path);
    }

    @Override
    public InputStream openRead(String path) {
        return get(path, null);
    }

    @Override
    public InputStream openRead(String path, long offset, long length) {
        final var last = length > Long.MAX_VALUE - offset ? "" : String.valueOf(offset + length - 1);
        return get(path, "bytes=" + offset + "-" + last);
    }

    /** @param range an HTTP {@code Range} value, or null for the whole object */
    private InputStream get(String path, String range) {
        try {
            final var response = client.getObject(r -> r.bucket(location.bucket()).key(key(path)).range(range));
            return new AbortingInputStream(response);
        } catch (S3Exception e) {
            if (e.statusCode() == NOT_FOUND) {
                throw new StorageException("“" + nameOf(path) + "” no longer exists.", e);
            }
            throw failure("read “" + nameOf(path) + "”", e);
        } catch (SdkException e) {
            throw failure("read “" + nameOf(path) + "”", e);
        }
    }

    // ---- changes --------------------------------------------------------------------------

    @Override
    public void createFolder(String parent, String name) {
        StoragePaths.requireValidName(name);
        requireFolder(parent);
        final var path = StoragePaths.child(parent, name);
        if (exists(path)) {
            throw new StorageException("“" + name + "” already exists here.");
        }
        try {
            client.putObject(r -> r.bucket(location.bucket()).key(folderKey(path)), RequestBody.empty());
        } catch (SdkException e) {
            throw failure("create “" + name + "”", e);
        }
    }

    @Override
    public void rename(String path, String newName) {
        StoragePaths.requireValidName(newName);
        final var target = StoragePaths.child(parentOf(path), newName);
        if (exists(target)) {
            throw new StorageException("“" + newName + "” already exists here.");
        }
        transfer(path, target, true, "rename");
    }

    // a copy overwrites an existing key, so the old file stays until the new one is in place
    @Override
    public void renameReplacing(String path, String newName) {
        StoragePaths.requireValidName(newName);
        final var target = StoragePaths.child(parentOf(path), newName);
        if (!exists(path)) {
            throw new StorageException("“" + nameOf(path) + "” no longer exists.");
        }
        if (headFile(path).isEmpty() || folderExists(target)) {
            throw new StorageException("“" + newName + "” can't be replaced: only a file replaces a file.");
        }
        final var what = "rename “" + nameOf(path) + "”";
        copyObject(key(path), key(target), what);
        deleteKeys(List.of(key(path)), what);
    }

    @Override
    public void move(String path, String destinationFolder, String targetName) {
        StoragePaths.requireValidName(targetName);
        if (destinationFolder.equals(path) || destinationFolder.startsWith(path + "/")) {
            throw new StorageException("A folder can't be moved into itself.");
        }
        requireFolder(destinationFolder);
        final var target = StoragePaths.child(destinationFolder, targetName);
        if (exists(target)) {
            throw new StorageException("“" + targetName + "” already exists in the destination.");
        }
        transfer(path, target, true, "move");
    }

    @Override
    public void copy(String path, String destinationFolder, String targetName) {
        StoragePaths.requireValidName(targetName);
        if (destinationFolder.equals(path) || destinationFolder.startsWith(path + "/")) {
            throw new StorageException("A folder can't be copied into itself.");
        }
        requireFolder(destinationFolder);
        final var target = StoragePaths.child(destinationFolder, targetName);
        if (exists(target)) {
            throw new StorageException("“" + targetName + "” already exists in the destination.");
        }
        transfer(path, target, false, "copy");
    }

    /** Copies a file, or everything under a folder, to {@code target}; then deletes the originals if moving. */
    private void transfer(String source, String target, boolean removeSource, String action) {
        final var what = action + " “" + nameOf(source) + "”";
        if (headFile(source).isPresent()) {
            copyObject(key(source), key(target), what);
            if (removeSource) {
                deleteKeys(List.of(key(source)), what);
            }
            return;
        }
        requireFolder(source);
        final var from = folderKey(source);
        final var to = folderKey(target);
        final var keys = keysUnder(from, what);
        for (final var key : keys) {
            copyObject(key, to + key.substring(from.length()), what);
        }
        if (removeSource) {
            deleteKeys(keys, what);
        }
    }

    private void copyObject(String from, String to, String what) {
        try {
            client.copyObject(r -> r.sourceBucket(location.bucket()).sourceKey(from)
                    .destinationBucket(location.bucket()).destinationKey(to));
        } catch (SdkException e) {
            throw failure(what, e);
        }
    }

    private List<String> keysUnder(String prefix, String what) {
        final var keys = new ArrayList<String>();
        try {
            client.listObjectsV2Paginator(r -> r.bucket(location.bucket()).prefix(prefix))
                    .contents()
                    .forEach(o -> keys.add(o.key()));
        } catch (SdkException e) {
            throw failure(what, e);
        }
        return keys;
    }

    private void deleteKeys(List<String> keys, String what) {
        for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
            final var batch = keys.subList(from, Math.min(keys.size(), from + DELETE_BATCH)).stream()
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .toList();
            try {
                final var result = client.deleteObjects(r -> r.bucket(location.bucket())
                        .delete(Delete.builder().objects(batch).quiet(Boolean.TRUE).build()));
                if (result.hasErrors() && !result.errors().isEmpty()) {
                    throw new StorageException("Could not " + what + ": " + result.errors().size()
                            + " items could not be deleted.");
                }
            } catch (SdkException e) {
                throw failure(what, e);
            }
        }
    }

    @Override
    public void delete(String path) {
        if (path.isEmpty()) {
            throw new StorageException("The root of a file system can't be deleted.");
        }
        final var what = "delete “" + nameOf(path) + "”";
        if (headFile(path).isPresent()) {
            deleteKeys(List.of(key(path)), what);
            return;
        }
        requireFolder(path);
        deleteKeys(keysUnder(folderKey(path), what), what);
    }

    // a folder is only its marker; an object written under it meanwhile keeps the folder in existence
    @Override
    public boolean deleteEmptyFolder(String path) {
        if (path.isEmpty()) {
            throw new StorageException("The root of a file system can't be deleted.");
        }
        final var what = "delete “" + nameOf(path) + "”";
        requireFolder(path);
        final var marker = folderKey(path);
        final var keys = keysUnder(marker, what);
        if (keys.stream().anyMatch(k -> !k.equals(marker))) {
            return false;
        }
        deleteKeys(keys, what);
        return true;
    }

    @Override
    public WriteHandle openWrite(String folder, String name, boolean replace) {
        StoragePaths.requireValidName(name);
        requireFolder(folder);
        final var path = StoragePaths.child(folder, name);
        if (folderExists(path)) {
            throw new StorageException("A folder named “" + name + "” already exists here.");
        }
        if (!replace && headFile(path).isPresent()) {
            throw new StorageException("“" + name + "” already exists here.");
        }
        return new MultipartWrite(path, name, replace);
    }

    /**
     * Sends the data in parts as it arrives; nothing is visible until {@link #commit()} completes
     * the upload. Small files are sent in one request.
     */
    private final class MultipartWrite implements WriteHandle {

        private final String path;
        private final String name;
        private final boolean replace;
        private final List<CompletedPart> parts = new ArrayList<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private String uploadId;
        private boolean done;

        @Getter
        private final OutputStream stream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                buffer.write(b);
                flushFullParts();
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                buffer.write(bytes, offset, length);
                flushFullParts();
            }
        };

        MultipartWrite(String path, String name, boolean replace) {
            this.path = path;
            this.name = name;
            this.replace = replace;
        }

        private void flushFullParts() throws IOException {
            while (buffer.size() >= PART_BYTES) {
                final var all = buffer.toByteArray();
                buffer.reset();
                buffer.write(all, PART_BYTES, all.length - PART_BYTES);
                uploadPart(Arrays.copyOf(all, PART_BYTES));
            }
        }

        private void uploadPart(byte[] part) throws IOException {
            try {
                if (uploadId == null) {
                    uploadId = client.createMultipartUpload(r -> r.bucket(location.bucket()).key(key(path))).uploadId();
                }
                final var number = parts.size() + 1;
                final var response = client.uploadPart(r -> r.bucket(location.bucket()).key(key(path))
                        .uploadId(uploadId).partNumber(number), RequestBody.fromBytes(part));
                parts.add(CompletedPart.builder().partNumber(number).eTag(response.eTag()).build());
            } catch (SdkException e) {
                throw new IOException("Could not upload “" + name + "”.", e);
            }
        }

        @Override
        public void commit() {
            if (!replace && headFile(path).isPresent()) {
                abort();
                throw new StorageException("“" + name + "” already exists here.");
            }
            try {
                if (uploadId == null) {
                    client.putObject(r -> r.bucket(location.bucket()).key(key(path)),
                            RequestBody.fromBytes(buffer.toByteArray()));
                } else {
                    uploadPart(buffer.toByteArray());
                    client.completeMultipartUpload(r -> r.bucket(location.bucket()).key(key(path)).uploadId(uploadId)
                            .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
                }
                done = true;
            } catch (IOException | SdkException e) {
                abort();
                throw new StorageException("“" + name + "” could not be saved.", e);
            }
        }

        @Override
        public void abort() {
            if (done) {
                return;
            }
            done = true;
            if (uploadId != null) {
                try {
                    client.abortMultipartUpload(r -> r.bucket(location.bucket()).key(key(path)).uploadId(uploadId));
                } catch (SdkException e) {
                    // the store expires abandoned uploads; nothing was made visible
                }
            }
        }
    }

    // ---- status ---------------------------------------------------------------------------

    /** Object storage is metered, not sized, and adding up a bucket means listing all of it. */
    @Override
    public Capacity capacity() {
        return null;
    }

    @Override
    public boolean healthy() {
        try {
            client.headBucket(r -> r.bucket(location.bucket()));
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    /** Whether writing is allowed can't be told without writing; the admin's access choice decides. */
    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public void close() {
        client.close();
    }

    private static StorageException failure(String action, SdkException e) {
        if (e instanceof S3Exception s3 && s3.statusCode() == FORBIDDEN) {
            return new StorageException("You don't have permission to " + action + ".", e);
        }
        if (e instanceof S3Exception s3 && s3.statusCode() == NOT_FOUND) {
            return new StorageException("Could not " + action + ": it no longer exists.", e);
        }
        return new StorageException("Could not " + action + ".", e);
    }

    /**
     * Closing the SDK's stream reads the rest of the object so the connection can be reused; one closed before
     * the end is aborted instead, so a dropped download or seek doesn't fetch what nobody reads.
     */
    private static final class AbortingInputStream extends FilterInputStream {

        private final ResponseInputStream<GetObjectResponse> response;
        private boolean ended;

        AbortingInputStream(ResponseInputStream<GetObjectResponse> response) {
            super(response);
            this.response = response;
        }

        @Override
        public int read() throws IOException {
            final var b = super.read();
            ended |= b < 0;
            return b;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            final var n = super.read(bytes, offset, length);
            ended |= n < 0;
            return n;
        }

        @Override
        public void close() throws IOException {
            if (ended) {
                super.close();
            } else {
                response.abort();
            }
        }
    }
}
