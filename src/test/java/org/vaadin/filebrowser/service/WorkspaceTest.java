package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.config.Roles;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.ExtractOptions;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.data.TestRoots;
import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.S3StorageType;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;

/** A view keeps the {@link FsRoot} it opened with; changes an admin makes later must still apply. */
class WorkspaceTest {

    @TempDir
    Path dir;

    private Path folder;
    private FileSystemRegistry registry;
    private Workspace workspace;
    private FsRoot held;
    private JdbcClient jdbc;
    private Catalog catalog;
    private StorageService storage;

    @BeforeEach
    void setUp() throws IOException {
        final var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        final var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);

        catalog = new Catalog();
        storage = new StorageService(new StorageTypes(List.of(new DiskStorageType(catalog)), catalog));
        registry = new FileSystemRegistry(jdbc, () -> true, () -> "admin@example.com", Clock.systemUTC(),
                storage::status);
        // no one is signed in during most tests, so nothing is saved per user
        workspace = workspace(new CurrentUser(null, null));

        folder = Files.createDirectory(dir.resolve("projects"));
        Files.writeString(folder.resolve("notes.txt"), "notes");
        final var definition = new FsRoot("a", "Projects", DiskStorageType.KEY, folder.toString(), Map.of(),
                Access.READ_WRITE, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        registry.add(definition, Map.of());
        held = registry.find("a").orElseThrow();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** Without a secret key, so no secrets can be stored. */
    private Workspace workspace(CurrentUser currentUser) {
        final var secrets = new RootSecrets(jdbc, new SecretBox((String) null));
        final var links = new LinkFetcher(address -> false, SystemDefaultDnsResolver.INSTANCE);
        return new Workspace(catalog, registry, storage, secrets, links, new UserData(jdbc), currentUser);
    }

    private static CurrentUser signedInAdmin() {
        final var idToken = OidcIdToken.withTokenValue("token")
                .subject("admin")
                .claim("email", "admin@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        final var authorities = List.of(new SimpleGrantedAuthority(Roles.ADMIN_AUTHORITY));
        final var user = new DefaultOidcUser(authorities, idToken);
        SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(user, authorities, "dex"));
        final var properties = new FileBrowserProperties(null, null, null, null, true, null, null, null, null, null, null);
        return new CurrentUser(null, properties);
    }

    private static void finish(TransferTask task) throws InterruptedException {
        final var deadline = Instant.now().plusSeconds(5);
        while (!task.finished() && Instant.now().isBefore(deadline)) {
            Thread.sleep(5);
        }
        assertThat(task.finished()).as("finished in time").isTrue();
    }

    private StorageEntry entry(String name) {
        return workspace.list(held, List.of()).stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aQueuedMoveTakesItsStarAlongAndAQueuedCopyDuplicatesInPlace() throws Exception {
        Files.createDirectory(folder.resolve("Archive"));
        workspace.setStarred("a", "notes.txt", true);
        final var notes = entry("notes.txt");

        final var copy = workspace.transfer(TransferTask.Kind.COPY, held, held, List.of(),
                List.of(new TransferTask.Item(notes, notes.name(), false, true)));
        finish(copy);
        final var move = workspace.transfer(TransferTask.Kind.MOVE, held, held, List.of("Archive"),
                List.of(new TransferTask.Item(notes, "notes.txt", false, false)));
        finish(move);

        assertThat(copy.getState()).isEqualTo(TransferTask.State.DONE);
        assertThat(move.getMessage()).isEqualTo("Moved “notes.txt” to “Archive”");
        assertThat(folder.resolve("notes copy.txt")).hasContent("notes");
        assertThat(folder.resolve("Archive/notes.txt")).hasContent("notes");
        assertThat(workspace.isStarred("a", "Archive/notes.txt")).isTrue();
    }

    @Test
    void aTransferOnAFileSystemLockedSinceItWasQueuedFails() throws Exception {
        final var notes = entry("notes.txt");
        final var blocker = Files.createDirectory(folder.resolve("Big"));
        Files.writeString(blocker.resolve("data.bin"), "x".repeat(1024));
        registry.update("a", "Projects", Access.LOCKED);
        final var task = workspace.getTransfers().enqueue(TransferTask.Kind.COPY, held, held, List.of("Big"),
                List.of(new TransferTask.Item(notes, "notes.txt", false, false)));
        finish(task);

        assertThat(task.getState()).isEqualTo(TransferTask.State.FAILED);
        assertThat(task.getMessage()).isEqualTo("“Projects” is locked. Nothing was copied.");
        assertThat(blocker.resolve("notes.txt")).doesNotExist();
    }

    /** A second disk file system, "Archive", with an "Inbox" folder. */
    private FsRoot archive() throws IOException {
        final var archiveFolder = Files.createDirectories(dir.resolve("archive/Inbox")).getParent();
        final var definition = new FsRoot("b", "Archive", DiskStorageType.KEY, archiveFolder.toString(), Map.of(),
                Access.READ_WRITE, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        registry.add(definition, Map.of());
        return registry.find("b").orElseThrow();
    }

    @Test
    void transfersGoToWritableFileSystemsWithTheCurrentOneFirst() throws Exception {
        final var archive = archive();

        assertThat(workspace.transferTargets(archive)).extracting(FsRoot::id).containsExactly("b", "a");
        registry.update("b", "Archive", Access.READ_ONLY);
        assertThat(workspace.transferTargets(archive)).extracting(FsRoot::id).containsExactly("a");
    }

    @Test
    void aCopyFromAReadOnlyFileSystemGoesToAnotherOne() throws Exception {
        final var archive = archive();
        registry.update("a", "Projects", Access.READ_ONLY);
        final var notes = entry("notes.txt");

        final var task = workspace.transfer(TransferTask.Kind.COPY, held, archive, List.of("Inbox"),
                List.of(new TransferTask.Item(notes, "notes.txt", false, false)));
        finish(task);

        assertThat(task.getMessage()).isEqualTo("Copied “notes.txt” to “Inbox” in “Archive”");
        assertThat(dir.resolve("archive/Inbox/notes.txt")).hasContent("notes");
        assertThat(folder.resolve("notes.txt")).hasContent("notes");
        assertThatThrownBy(() -> workspace.transfer(TransferTask.Kind.MOVE, held, archive, List.of("Inbox"),
                List.of(new TransferTask.Item(notes, "notes.txt", false, false)))).hasMessageContaining("read only");
    }

    @Test
    void aMoveToAnotherFileSystemTakesTheFilesAndTheirStars() throws Exception {
        final var archive = archive();
        Files.createDirectories(folder.resolve("Reports/2025"));
        Files.writeString(folder.resolve("Reports/2025/q1.txt"), "q1");
        workspace.setStarred("a", "Reports/2025/q1.txt", true);
        workspace.setStarred("a", "Reports", true);
        final var reports = entry("Reports");

        final var task = workspace.transfer(TransferTask.Kind.MOVE, held, archive, List.of("Inbox"),
                List.of(new TransferTask.Item(reports, "Reports", false, false)));
        finish(task);

        assertThat(task.getState()).isEqualTo(TransferTask.State.DONE);
        assertThat(task.progress()).isEqualTo(100);
        assertThat(dir.resolve("archive/Inbox/Reports/2025/q1.txt")).hasContent("q1");
        assertThat(folder.resolve("Reports")).doesNotExist();
        assertThat(workspace.isStarred("b", "Inbox/Reports/2025/q1.txt")).isTrue();
        assertThat(workspace.isStarred("b", "Inbox/Reports")).isTrue();
        assertThat(workspace.isStarred("a", "Reports")).isFalse();
    }

    @Test
    void someoneWhoHasNotChosenALayoutSeesTheGrid() {
        assertThat(workspace.getLayout()).isEqualTo(Workspace.Layout.GRID);
    }

    @Test
    void writesAreRefusedOnceTheFileSystemIsMadeReadOnly() {
        registry.update("a", "Projects", Access.READ_ONLY);

        assertThatThrownBy(() -> workspace.createFolder(held, List.of(), "Research"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("read only");
        final var file = new IncomingFile("c1", "upload.txt", "text", 5);
        final var planned = List.of(new UploadQueue.Planned(file, "upload.txt", false));
        assertThatThrownBy(() -> workspace.upload(held, List.of(), planned, Long.MAX_VALUE)).isInstanceOf(StorageException.class);
        assertThat(folder.resolve("Research")).doesNotExist();
        assertThat(workspace.list(held, List.of())).extracting(e -> e.name()).containsExactly("notes.txt");
    }

    @Test
    void connectionLimitsOutsideTheAllowedRangeAreNotSaved() {
        final var tooFew = new RootOptions(true, true, true, 2, 1, TestRoots.IDLE_STREAM_SECONDS);
        assertThatThrownBy(() -> workspace.updateOptions(held, tooFew))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Maximum open connections");
        assertThat(registry.find("a").orElseThrow().options()).isEqualTo(TestRoots.ALL_ALLOWED);
    }

    @Test
    void linkUploadsAndExtractionAreRefusedOnceTurnedOff() {
        registry.updateOptions("a", TestRoots.features(false, false, true));

        assertThatThrownBy(() -> workspace.inspectLink(held, "https://example.com/report.pdf"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("web links are turned off");
        final var link = IncomingFile.link("https://example.com/report.pdf", "report.pdf", "pdf", 5L);
        final var fromLink = List.of(new UploadQueue.Planned(link, "report.pdf", false));
        assertThatThrownBy(() -> workspace.upload(held, List.of(), fromLink, Long.MAX_VALUE))
                .hasMessageContaining("web links are turned off");
        final var archive = new IncomingFile("c1", "photos.zip", "archive", 5);
        final var match = catalog.archiveFormat("photos.zip").orElseThrow();
        final var extraction = new UploadQueue.Extraction(match.format(), match.baseName(), "photos", false,
                ExtractOptions.Clash.KEEP_BOTH, new ArchiveExtractor.Limits(Long.MAX_VALUE, 10));
        final var extracted = List.of(new UploadQueue.Planned(archive, "photos.zip", false, extraction));
        assertThatThrownBy(() -> workspace.upload(held, List.of(), extracted, Long.MAX_VALUE))
                .hasMessageContaining("Extracting archives is turned off");
        assertThat(workspace.getUploads().all()).isEmpty();

        final var plain = List.of(new UploadQueue.Planned(archive, "photos.zip", false));
        assertThat(workspace.upload(held, List.of(), plain, Long.MAX_VALUE)).hasSize(1);
    }

    @Test
    void aRemovedFileSystemCanNoLongerBeListedOrChanged() {
        registry.remove("a");

        assertThatThrownBy(() -> workspace.list(held, List.of()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("removed");
        assertThatThrownBy(() -> workspace.createFolder(held, List.of(), "Research"))
                .isInstanceOf(StorageException.class);
        assertThat(folder.resolve("notes.txt")).hasContent("notes");
    }

    @Test
    void currentReflectsARename() {
        registry.update("a", "Team projects", Access.READ_WRITE);
        assertThat(workspace.current(held).name()).isEqualTo("Team projects");
    }

    @Test
    void aMoveThatReplacesDeletesTheExistingFileAndItsStar() throws IOException {
        Files.createDirectory(folder.resolve("Archive"));
        Files.writeString(folder.resolve("Archive/notes.txt"), "older");
        workspace.setStarred("a", "notes.txt", true);
        workspace.setStarred("a", "Archive/notes.txt", true);
        final var notes = workspace.list(held, List.of()).stream().filter(e -> e.name().equals("notes.txt")).findFirst().orElseThrow();

        workspace.move(held, notes, List.of("Archive"), "notes.txt", true);

        assertThat(folder.resolve("Archive/notes.txt")).hasContent("notes");
        assertThat(folder.resolve("notes.txt")).doesNotExist();
        assertThat(workspace.isStarred("a", "Archive/notes.txt")).isTrue();
        assertThat(workspace.isStarred("a", "notes.txt")).isFalse();
    }

    @Test
    void aMoveUnderANewNameTakesItsStarAlong() throws IOException {
        Files.createDirectory(folder.resolve("Archive"));
        Files.writeString(folder.resolve("Archive/notes.txt"), "older");
        workspace.setStarred("a", "notes.txt", true);
        final var notes = workspace.list(held, List.of()).stream().filter(e -> e.name().equals("notes.txt")).findFirst().orElseThrow();

        workspace.move(held, notes, List.of("Archive"), "notes (1).txt", false);

        assertThat(folder.resolve("Archive/notes (1).txt")).hasContent("notes");
        assertThat(folder.resolve("Archive/notes.txt")).hasContent("older");
        assertThat(workspace.isStarred("a", "Archive/notes (1).txt")).isTrue();
    }

    @Test
    void aFolderIsNeverReplaced() throws IOException {
        Files.createDirectories(folder.resolve("Archive/notes.txt"));
        final var notes = workspace.list(held, List.of()).stream().filter(e -> e.name().equals("notes.txt")).findFirst().orElseThrow();

        assertThatThrownBy(() -> workspace.move(held, notes, List.of("Archive"), "notes.txt", true))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("can't be replaced");
        assertThat(folder.resolve("Archive/notes.txt")).isDirectory();
        assertThat(folder.resolve("notes.txt")).hasContent("notes");
    }

    @Test
    void aCopyThatReplacesKeepsTheOriginalAndItsStar() throws IOException {
        Files.createDirectory(folder.resolve("Archive"));
        Files.writeString(folder.resolve("Archive/notes.txt"), "older");
        workspace.setStarred("a", "notes.txt", true);
        workspace.setStarred("a", "Archive/notes.txt", true);
        final var notes = workspace.list(held, List.of()).stream().filter(e -> e.name().equals("notes.txt")).findFirst().orElseThrow();

        workspace.copy(held, notes, List.of("Archive"), "notes.txt", true);

        assertThat(folder.resolve("Archive/notes.txt")).hasContent("notes");
        assertThat(folder.resolve("notes.txt")).hasContent("notes");
        assertThat(workspace.isStarred("a", "notes.txt")).isTrue();
        assertThat(workspace.isStarred("a", "Archive/notes.txt")).isFalse();
    }

    @Test
    void aDuplicateIsNamedCopyNextToTheOriginal() {
        final var notes = workspace.list(held, List.of()).stream().filter(e -> e.name().equals("notes.txt")).findFirst().orElseThrow();

        assertThat(workspace.duplicate(held, notes)).isEqualTo("notes copy.txt");
        assertThat(workspace.duplicate(held, notes)).isEqualTo("notes copy 2.txt");
        assertThat(folder.resolve("notes copy 2.txt")).hasContent("notes");
    }

    @Test
    void onlyAdminsMayBrowseTheServersFolders() {
        // no one is signed in here, so the current user is not an admin
        assertThatThrownBy(() -> workspace.serverFolders(dir.toString())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aFileSystemWithSecretsIsNotAddedWithoutASecretKey() {
        final var bucket = new FsRoot("b", "Assets", S3StorageType.KEY, "s3://assets", Map.of(), Access.READ_WRITE,
                TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        final var keys = Map.of(S3StorageType.ACCESS_KEY, "AKIDEXAMPLE", S3StorageType.SECRET_KEY, "wJalrXUtnFEMI");
        assertThatThrownBy(() -> workspace.addRoot(bucket, keys)).isInstanceOf(StorageException.class);
        assertThat(registry.all()).extracting(FsRoot::id).containsExactly("a");
    }

    @Test
    void anAdminIsToldWhenKeysCannotBeStored() {
        final var admin = workspace(signedInAdmin());
        final var keys = Map.of(S3StorageType.ACCESS_KEY, "AKIDEXAMPLE", S3StorageType.SECRET_KEY, "wJalrXUtnFEMI");
        final var config = new StorageConfig("s3://assets", Map.of(S3StorageType.REGION, "eu-central-1"), keys,
                TestRoots.MAX_CONNECTIONS);
        assertThat(admin.checkRoot(S3StorageType.KEY, config).problem()).contains("FILEBROWSER_SECRET_KEY_FILE");
    }

    @Test
    void onlyAdminsCanCheckAFileSystemBeforeAddingIt() {
        final var config = new StorageConfig(folder.toString(), Map.of(), Map.of(), TestRoots.MAX_CONNECTIONS);
        assertThatThrownBy(() -> workspace.checkRoot(DiskStorageType.KEY, config))
                .isInstanceOf(AccessDeniedException.class);
    }
}
