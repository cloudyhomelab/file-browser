package org.vaadin.filebrowser.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
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
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.storage.MountInfo;

/** The configured file systems, shared by every user. Only admins may add to it. */
@Service
public class FileSystemRegistry {

    private final JdbcClient jdbc;
    private final BooleanSupplier isAdmin;
    private final Supplier<String> currentEmail;
    private final Clock clock;
    private final Function<FsRoot, StorageService.RootStatus> status;

    @Autowired
    public FileSystemRegistry(JdbcClient jdbc, CurrentUser currentUser, StorageService storage) {
        this(jdbc, currentUser::isAdmin, () -> currentUser.get().map(CurrentUser.User::email).orElse(""),
                Clock.systemUTC(), storage::status);
    }

    FileSystemRegistry(JdbcClient jdbc, BooleanSupplier isAdmin, Supplier<String> currentEmail, Clock clock,
                       Function<FsRoot, StorageService.RootStatus> status) {
        this.jdbc = jdbc;
        this.status = status;
        this.isAdmin = isAdmin;
        this.currentEmail = currentEmail;
        this.clock = clock;
    }

    public List<FsRoot> all() {
        return jdbc.sql("SELECT * FROM file_system ORDER BY created_at, name").query(this::map).list();
    }

    public Optional<FsRoot> find(String id) {
        return jdbc.sql("SELECT * FROM file_system WHERE id = :id").param("id", id).query(this::map).optional();
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

    public void add(FsRoot root) {
        if (!isAdmin.getAsBoolean()) {
            throw new AccessDeniedException("Only admins can add file systems");
        }
        jdbc.sql("""
                        INSERT INTO file_system (id, name, type, endpoint, region, access, created_by, created_at)
                        VALUES (:id, :name, :type, :endpoint, :region, :access, :createdBy, :createdAt)
                        """)
                .param("id", root.id())
                .param("name", root.name())
                .param("type", root.type().name())
                .param("endpoint", root.endpoint())
                .param("region", root.region())
                .param("access", root.access().name())
                .param("createdBy", currentEmail.get())
                .param("createdAt", clock.instant().toString())
                .update();
    }

    // capacity, online state and mount are read from the storage, not stored
    private FsRoot map(ResultSet rs, int row) throws SQLException {
        final var type = RootType.valueOf(rs.getString("type"));
        final var access = Access.valueOf(rs.getString("access"));
        final var definition = new FsRoot(rs.getString("id"), rs.getString("name"), type, rs.getString("endpoint"),
                rs.getString("region"), null, false, access, MountInfo.UNKNOWN);
        final var current = status.apply(definition);
        return new FsRoot(definition.id(), definition.name(), type, definition.endpoint(), definition.region(),
                current.capacity(), current.online(), access, current.mount());
    }
}
