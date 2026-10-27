package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Runs against RustFS, an S3-compatible server; skipped where Docker isn't available (e.g. the image build). */
@Testcontainers(disabledWithoutDocker = true)
class S3ProviderTest {

    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";
    private static final String REGION = "us-east-1";
    private static final int API_PORT = 9000;

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>("rustfs/rustfs:1.0.1")
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withExposedPorts(API_PORT)
            .waitingFor(Wait.forHttp("/health").forPort(API_PORT));

    private S3Client admin;
    private String bucket;
    private S3Provider provider;

    private static URI endpointUrl() {
        return URI.create("http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(API_PORT));
    }

    private static S3Provider provider(String endpoint, String accessKey, String secretKey) {
        final var connection = new S3Provider.Connection(endpoint, REGION, endpointUrl(), accessKey, secretKey);
        return new S3Provider(connection, name -> name.endsWith(".txt") ? "text" : "generic");
    }

    private void put(String key, String content) {
        admin.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(content));
    }

    private static String read(S3Provider p, String path) throws IOException {
        try (final var in = p.openRead(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setUp() {
        admin = S3Client.builder()
                .region(Region.of(REGION))
                .endpointOverride(endpointUrl())
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
        bucket = "test-" + UUID.randomUUID().toString().substring(0, 8);
        admin.createBucket(r -> r.bucket(bucket));
        put("Projects/Design/logo.txt", "logo");
        put("Projects/brief.txt", "brief");
        put("notes.txt", "notes");
        provider = provider("s3://" + bucket, ACCESS_KEY, SECRET_KEY);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        admin.close();
    }

    @Test
    void parsesBucketAndFolderLocations() {
        assertThat(S3Provider.Location.parse("s3://bucket")).isEqualTo(new S3Provider.Location("bucket", ""));
        assertThat(S3Provider.Location.parse("s3://bucket/team/a/")).isEqualTo(new S3Provider.Location("bucket", "team/a/"));
        assertThat(S3Provider.Location.parse("s3://")).isNull();
        assertThat(S3Provider.Location.parse("/srv/data")).isNull();
    }

    @Test
    void listsFoldersAndFilesWithRelativePaths() {
        final var entries = provider.list("");
        assertThat(entries).extracting(StorageEntry::path).containsExactlyInAnyOrder("Projects", "notes.txt");
        final var notes = provider.stat("notes.txt").orElseThrow();
        assertThat(notes.type()).isEqualTo("text");
        assertThat(notes.size()).isEqualTo(5L);
        assertThat(notes.modified()).isNotNull();
        assertThat(provider.list("Projects")).extracting(StorageEntry::path)
                .containsExactlyInAnyOrder("Projects/Design", "Projects/brief.txt");
        assertThat(provider.stat("Projects").orElseThrow().folder()).isTrue();
        assertThat(provider.stat("missing.txt")).isEmpty();
        assertThatThrownBy(() -> provider.list("missing")).isInstanceOf(StorageException.class);
    }

    @Test
    void aFolderRootSeesOnlyItsFolder() throws IOException {
        final var team = provider("s3://" + bucket + "/Projects", ACCESS_KEY, SECRET_KEY);
        assertThat(team.list("")).extracting(StorageEntry::path).containsExactlyInAnyOrder("Design", "brief.txt");
        assertThat(read(team, "brief.txt")).isEqualTo("brief");
        assertThat(team.stat("notes.txt")).isEmpty();
        team.close();
    }

    @Test
    void refusesControlCharactersAndDotSegments() {
        assertThatThrownBy(() -> provider.createFolder("", "a\r\nb")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.openRead("notes.txt\n")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.list("..")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.stat("Projects/../notes.txt")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.delete("Projects//x")).isInstanceOf(StorageException.class);
        assertThat(provider.stat("notes.txt")).isPresent();
    }

    @Test
    void createsEmptyFoldersAndRefusesNameClashes() {
        provider.createFolder("Projects", "Research");
        assertThat(provider.list("Projects")).extracting(StorageEntry::name).contains("Research");
        assertThat(provider.list("Projects/Research")).isEmpty();
        assertThatThrownBy(() -> provider.createFolder("Projects", "Research")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.createFolder("", "..")).isInstanceOf(StorageException.class);
    }

    @Test
    void renamesMovesAndCopiesFilesAndFolders() throws IOException {
        provider.rename("notes.txt", "todo.txt");
        assertThat(provider.stat("notes.txt")).isEmpty();
        assertThat(read(provider, "todo.txt")).isEqualTo("notes");

        provider.rename("Projects", "Work");
        assertThat(read(provider, "Work/Design/logo.txt")).isEqualTo("logo");
        assertThat(provider.stat("Projects")).isEmpty();

        provider.move("todo.txt", "Work/Design");
        assertThat(read(provider, "Work/Design/todo.txt")).isEqualTo("notes");
        assertThatThrownBy(() -> provider.move("Work", "Work/Design")).isInstanceOf(StorageException.class);

        provider.copy("Work", "Work copy");
        assertThat(read(provider, "Work copy/brief.txt")).isEqualTo("brief");
        assertThat(read(provider, "Work/brief.txt")).isEqualTo("brief");
        assertThatThrownBy(() -> provider.copy("Work/brief.txt", "Design")).isInstanceOf(StorageException.class);
    }

    @Test
    void deletesFoldersWithTheirContentsButNeverTheRoot() {
        provider.delete("Projects");
        assertThat(provider.stat("Projects")).isEmpty();
        assertThat(provider.stat("Projects/Design/logo.txt")).isEmpty();
        assertThatThrownBy(() -> provider.delete("")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.delete("missing")).isInstanceOf(StorageException.class);
    }

    @Test
    void writesBecomeVisibleOnlyOnCommit() throws IOException {
        try (final var write = provider.openWrite("", "upload.txt", false)) {
            write.stream().write("hello".getBytes(StandardCharsets.UTF_8));
            assertThat(provider.stat("upload.txt")).isEmpty();
            write.commit();
        }
        assertThat(read(provider, "upload.txt")).isEqualTo("hello");
    }

    @Test
    void largeWritesGoUpInPartsAndAbortedOnesLeaveNothing() throws IOException {
        final var data = new byte[20 * 1024 * 1024];
        Arrays.fill(data, (byte) 'x');
        try (final var write = provider.openWrite("Projects", "big.bin", false)) {
            write.stream().write(data);
            write.commit();
        }
        assertThat(provider.stat("Projects/big.bin").orElseThrow().size()).isEqualTo((long) data.length);

        final var aborted = provider.openWrite("", "partial.bin", false);
        aborted.stream().write(data);
        aborted.abort();
        assertThat(provider.stat("partial.bin")).isEmpty();
    }

    @Test
    void replaceOverwritesAndPlainWriteRefusesAnExistingFile() throws IOException {
        assertThatThrownBy(() -> provider.openWrite("", "notes.txt", false)).isInstanceOf(StorageException.class);
        try (final var write = provider.openWrite("", "notes.txt", true)) {
            write.stream().write("new".getBytes(StandardCharsets.UTF_8));
            write.commit();
        }
        assertThat(read(provider, "notes.txt")).isEqualTo("new");
    }

    @Test
    void reportsStatusAndRefusesWrongKeys() {
        assertThat(provider.healthy()).isTrue();
        assertThat(provider.capacity()).isNull();
        assertThat(provider.mount()).isEmpty();
        final var wrong = provider("s3://" + bucket, ACCESS_KEY, "not-the-secret");
        assertThat(wrong.healthy()).isFalse();
        assertThatThrownBy(() -> wrong.list("")).isInstanceOf(StorageException.class);
        wrong.close();
    }

    @Test
    void anonymousAccessReadsAPublicBucketButCannotWrite() throws IOException {
        final var policy = """
                {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": "*",
                  "Action": ["s3:GetBucketLocation", "s3:ListBucket", "s3:GetObject"],
                  "Resource": ["arn:aws:s3:::%s", "arn:aws:s3:::%s/*"]}]}
                """.formatted(bucket, bucket);
        admin.putBucketPolicy(r -> r.bucket(bucket).policy(policy));

        final var anonymous = provider("s3://" + bucket, null, null);
        assertThat(anonymous.healthy()).isTrue();
        assertThat(anonymous.list("")).extracting(StorageEntry::path).containsExactlyInAnyOrder("Projects", "notes.txt");
        assertThat(read(anonymous, "notes.txt")).isEqualTo("notes");
        assertThatThrownBy(() -> anonymous.createFolder("", "Nope"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("permission");
        anonymous.close();
    }

    @Test
    void anonymousAccessToAPrivateBucketIsRefused() {
        final var anonymous = provider("s3://" + bucket, null, null);
        assertThat(anonymous.healthy()).isFalse();
        assertThatThrownBy(() -> anonymous.list("")).isInstanceOf(StorageException.class);
        anonymous.close();
    }
}
