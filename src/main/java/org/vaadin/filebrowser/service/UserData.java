package org.vaadin.filebrowser.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;
import org.vaadin.filebrowser.storage.StoragePaths;

/** Per-user starred items and view preferences, keyed by lower-cased email. */
@Repository
public class UserData {

    private final JdbcClient jdbc;

    public record Preferences(Workspace.Layout layout, Workspace.Density density, Sort sort, boolean groupByType) {

        public static final Preferences DEFAULT =
                new Preferences(Workspace.Layout.GRID, Workspace.Density.DENSE, Sort.DEFAULT, false);
    }

    public UserData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // ---- starred --------------------------------------------------------------------------

    /** Keys {@code rootId + ":" + path} of everything the user starred. */
    public Set<String> starred(String email) {
        final var keys = jdbc.sql("SELECT root_id, path FROM starred_item WHERE email = :email")
                .param("email", normalize(email))
                .query((rs, row) -> rs.getString("root_id") + ":" + rs.getString("path"))
                .list();
        return new HashSet<>(keys);
    }

    public void star(String email, String rootId, String path) {
        jdbc.sql("INSERT OR IGNORE INTO starred_item (email, root_id, path) VALUES (:email, :root, :path)")
                .param("email", normalize(email))
                .param("root", rootId)
                .param("path", path)
                .update();
    }

    public void unstar(String email, String rootId, String path) {
        jdbc.sql("DELETE FROM starred_item WHERE email = :email AND root_id = :root AND path = :path")
                .param("email", normalize(email))
                .param("root", rootId)
                .param("path", path)
                .update();
    }

    /**
     * Re-points every user's stars on {@code oldPath} and anything inside it to {@code newPath}; a
     * rename or move changes the path for everyone because file systems are shared.
     */
    public void moveStars(String rootId, String oldPath, String newPath) {
        // substr comparison instead of LIKE, so % and _ in names are not wildcards
        jdbc.sql("""
                        UPDATE OR REPLACE starred_item
                        SET path = :newPath || substr(path, length(:oldPath) + 1)
                        WHERE root_id = :root
                          AND (path = :oldPath OR substr(path, 1, length(:oldPath) + 1) = :oldPath || :sep)
                        """)
                .param("root", rootId)
                .param("oldPath", oldPath)
                .param("newPath", newPath)
                .param("sep", StoragePaths.SEPARATOR)
                .update();
    }

    /** Re-points every user's stars on exactly {@code path} to {@code newPath} on another file system. */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void moveStarAcross(String rootId, String path, String newRootId, String newPath) {
        jdbc.sql("""
                        UPDATE OR REPLACE starred_item
                        SET root_id = :newRoot, path = :newPath
                        WHERE root_id = :root AND path = :path
                        """)
                .param("root", rootId)
                .param("path", path)
                .param("newRoot", newRootId)
                .param("newPath", newPath)
                .update();
    }

    /** Drops every user's stars on {@code path} and anything inside it. */
    public void dropStars(String rootId, String path) {
        jdbc.sql("""
                        DELETE FROM starred_item
                        WHERE root_id = :root
                          AND (path = :path OR substr(path, 1, length(:path) + 1) = :path || :sep)
                        """)
                .param("root", rootId)
                .param("path", path)
                .param("sep", StoragePaths.SEPARATOR)
                .update();
    }

    // ---- preferences ----------------------------------------------------------------------

    public Optional<Preferences> preferences(String email) {
        return jdbc.sql("SELECT * FROM user_preference WHERE email = :email")
                .param("email", normalize(email))
                .query((rs, row) -> {
                    final var layout = Workspace.Layout.valueOf(rs.getString("layout"));
                    final var density = Workspace.Density.valueOf(rs.getString("density"));
                    final var sort = new Sort(SortKey.valueOf(rs.getString("sort_key")), rs.getInt("sort_ascending") != 0);
                    return new Preferences(layout, density, sort, rs.getInt("group_by_type") != 0);
                })
                .optional();
    }

    public void savePreferences(String email, Preferences preferences) {
        jdbc.sql("""
                        INSERT INTO user_preference (email, layout, density, sort_key, sort_ascending, group_by_type)
                        VALUES (:email, :layout, :density, :sortKey, :ascending, :groupByType)
                        ON CONFLICT (email) DO UPDATE SET
                            layout = excluded.layout,
                            density = excluded.density,
                            sort_key = excluded.sort_key,
                            sort_ascending = excluded.sort_ascending,
                            group_by_type = excluded.group_by_type
                        """)
                .param("email", normalize(email))
                .param("layout", preferences.layout().name())
                .param("density", preferences.density().name())
                .param("sortKey", preferences.sort().key().name())
                .param("ascending", preferences.sort().ascending() ? 1 : 0)
                .param("groupByType", preferences.groupByType() ? 1 : 0)
                .update();
    }
}
