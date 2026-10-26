package org.vaadin.filebrowser.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;

/** The configured file systems, shared by every user. Only admins may add to it. */
@Service
public class FileSystemRegistry {

    private final JdbcClient jdbc;
    private final Catalog catalog;
    private final BooleanSupplier isAdmin;
    private final Supplier<String> currentEmail;
    private final Clock clock;

    @Autowired
    public FileSystemRegistry(JdbcClient jdbc, Catalog catalog, CurrentUser currentUser) {
        this(jdbc, catalog, currentUser::isAdmin,
                () -> currentUser.get().map(CurrentUser.User::email).orElse(""), Clock.systemUTC());
    }

    FileSystemRegistry(JdbcClient jdbc, Catalog catalog, BooleanSupplier isAdmin, Supplier<String> currentEmail,
                       Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
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

    // capacity and online state are not stored; until the storage providers exist they come from the type defaults
    private FsRoot map(ResultSet rs, int row) throws SQLException {
        final var type = RootType.valueOf(rs.getString("type"));
        final var access = Access.valueOf(rs.getString("access"));
        final var capacity = catalog.rootType(type).newCapacity();
        return new FsRoot(rs.getString("id"), rs.getString("name"), type, rs.getString("endpoint"),
                rs.getString("region"), capacity, true, access);
    }
}
