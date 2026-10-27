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
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.S3StorageType;
import org.vaadin.filebrowser.storage.StorageConfig;
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
                Access.READ_WRITE, RootStatus.UNKNOWN);
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
    void onlyAdminsMayBrowseTheServersFolders() {
        // no one is signed in here, so the current user is not an admin
        assertThatThrownBy(() -> workspace.serverFolders(dir.toString())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aFileSystemWithSecretsIsNotAddedWithoutASecretKey() {
        final var bucket = new FsRoot("b", "Assets", S3StorageType.KEY, "s3://assets", Map.of(), Access.READ_WRITE,
                RootStatus.UNKNOWN);
        final var keys = Map.of(S3StorageType.ACCESS_KEY, "AKIDEXAMPLE", S3StorageType.SECRET_KEY, "wJalrXUtnFEMI");
        assertThatThrownBy(() -> workspace.addRoot(bucket, keys)).isInstanceOf(StorageException.class);
        assertThat(registry.all()).extracting(FsRoot::id).containsExactly("a");
    }

    @Test
    void anAdminIsToldWhenKeysCannotBeStored() {
        final var admin = workspace(signedInAdmin());
        final var keys = Map.of(S3StorageType.ACCESS_KEY, "AKIDEXAMPLE", S3StorageType.SECRET_KEY, "wJalrXUtnFEMI");
        final var config = new StorageConfig("s3://assets", Map.of(S3StorageType.REGION, "eu-central-1"), keys);
        assertThat(admin.checkRoot(S3StorageType.KEY, config)).contains("FILEBROWSER_SECRET_KEY_FILE");
    }

    @Test
    void onlyAdminsCanCheckAFileSystemBeforeAddingIt() {
        final var config = new StorageConfig(folder.toString(), Map.of(), Map.of());
        assertThatThrownBy(() -> workspace.checkRoot(DiskStorageType.KEY, config))
                .isInstanceOf(AccessDeniedException.class);
    }
}
