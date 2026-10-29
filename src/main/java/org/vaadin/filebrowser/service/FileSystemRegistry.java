package org.vaadin.filebrowser.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.data.RootStatus;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The configured file systems, shared by every user. Only admins may add to it. */
@Service
public class FileSystemRegistry {

    /** Everything but the secrets, which only {@link RootSecrets} reads. */
    private static final String COLUMNS = "id, name, type, endpoint, settings, access, options";
    // every option must be stored: a file system only ever reads its own
    private static final JsonMapper OPTIONS = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .build();

    private final JdbcClient jdbc;
    private final BooleanSupplier adminCheck;
    private final Supplier<String> currentEmail;
    private final Clock clock;
    private final Function<FsRoot, RootStatus> status;

    @Autowired
    public FileSystemRegistry(JdbcClient jdbc, CurrentUser currentUser, StorageService storage) {
        this(jdbc, currentUser::isAdmin, () -> currentUser.get().map(CurrentUser.User::email).orElse(""),
                Clock.systemUTC(), storage::status);
    }

    FileSystemRegistry(JdbcClient jdbc, BooleanSupplier adminCheck, Supplier<String> currentEmail, Clock clock,
                       Function<FsRoot, RootStatus> status) {
        this.jdbc = jdbc;
        this.status = status;
        this.adminCheck = adminCheck;
        this.currentEmail = currentEmail;
        this.clock = clock;
    }

    public List<FsRoot> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM file_system ORDER BY created_at, name").query((rs, row) -> map(rs)).list();
    }

    public Optional<FsRoot> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM file_system WHERE id = :id").param("id", id).query((rs, row) -> map(rs)).optional();
    }

    public boolean endpointTaken(String endpoint) {
        final var count = jdbc.sql("SELECT COUNT(*) FROM file_system WHERE endpoint = :endpoint")
                .param("endpoint", endpoint)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public String newId() {
        return UUID.randomUUID().toString();
    }

    /** @param sealedSecrets its type's secrets as {@link RootSecrets#seal} returned them; empty for none */
    public void add(FsRoot root, Map<String, String> sealedSecrets) {
        if (!adminCheck.getAsBoolean()) {
            throw new AccessDeniedException("Only admins can add file systems");
        }
        jdbc.sql("""
                        INSERT INTO file_system (id, name, type, endpoint, settings, secrets, access, options,
                                                 created_by, created_at)
                        VALUES (:id, :name, :type, :endpoint, :settings, :secrets, :access, :options, :createdBy,
                                :createdAt)
                        """)
                .param("id", root.id())
                .param("name", root.name())
                .param("type", root.type())
                .param("endpoint", root.endpoint())
                .param("settings", JsonMaps.write(root.settings()))
                .param("secrets", JsonMaps.write(sealedSecrets))
                .param("access", root.access().name())
                .param("options", OPTIONS.writeValueAsString(root.options()))
                .param("createdBy", currentEmail.get())
                .param("createdAt", clock.instant().toString())
                .update();
    }

    /** Renames it and changes its access; the path never changes, since stars are stored relative to it. */
    public void update(String id, String name, Access access) {
        if (!adminCheck.getAsBoolean()) {
            throw new AccessDeniedException("Only admins can change file systems");
        }
        jdbc.sql("UPDATE file_system SET name = :name, access = :access WHERE id = :id")
                .param("id", id)
                .param("name", name)
                .param("access", access.name())
                .update();
    }

    public void updateOptions(String id, RootOptions options) {
        if (!adminCheck.getAsBoolean()) {
            throw new AccessDeniedException("Only admins can change file systems");
        }
        jdbc.sql("UPDATE file_system SET options = :options WHERE id = :id")
                .param("id", id)
                .param("options", OPTIONS.writeValueAsString(options))
                .update();
    }

    /** Unregisters it; the files stay where they are. Stars on it go with it (foreign key cascade). */
    public void remove(String id) {
        if (!adminCheck.getAsBoolean()) {
            throw new AccessDeniedException("Only admins can remove file systems");
        }
        jdbc.sql("DELETE FROM file_system WHERE id = :id").param("id", id).update();
    }

    // the status is read from the storage, not stored
    private FsRoot map(ResultSet rs) throws SQLException {
        final var access = Access.valueOf(rs.getString("access"));
        final var settings = JsonMaps.read(rs.getString("settings"));
        final var options = OPTIONS.readValue(rs.getString("options"), RootOptions.class);
        final var definition = new FsRoot(rs.getString("id"), rs.getString("name"), rs.getString("type"),
                rs.getString("endpoint"), settings, access, options, RootStatus.UNKNOWN);
        final var current = status.apply(definition);
        return new FsRoot(definition.id(), definition.name(), definition.type(), definition.endpoint(), settings,
                access, options, current);
    }
}
