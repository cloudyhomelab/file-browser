package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;

class UserDataTest {

    @TempDir
    Path dir;

    private JdbcClient jdbc;
    private UserData data;

    @BeforeEach
    void migrate() {
        final var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        final var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        final var flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO file_system (id, name, type, endpoint, access, created_by, created_at)
                VALUES ('r', 'Root', 'disk', '/srv', 'READ_WRITE', 'a@x.org', '2026-10-26T00:00:00Z')
                """).update();
        data = new UserData(jdbc);
    }

    @Test
    void starsArePerUserAndIgnoreEmailCase() {
        data.star("Alice@Example.com", "r", "Projects");
        data.star("alice@example.com", "r", "Projects");
        data.star("bob@example.com", "r", "notes.txt");
        assertThat(data.starred("ALICE@example.com")).containsExactly("r:Projects");
        data.unstar("alice@example.com", "r", "Projects");
        assertThat(data.starred("alice@example.com")).isEmpty();
        assertThat(data.starred("bob@example.com")).containsExactly("r:notes.txt");
    }

    @Test
    void movingAFolderMovesEveryUsersStarsInsideIt() {
        data.star("a@x.org", "r", "Projects");
        data.star("a@x.org", "r", "Projects/brief.txt");
        data.star("b@x.org", "r", "Projects/Design/logo.svg");
        data.star("b@x.org", "r", "Projects-old/keep.txt");
        data.moveStars("r", "Projects", "Archive/Projects");
        assertThat(data.starred("a@x.org")).containsExactlyInAnyOrder("r:Archive/Projects", "r:Archive/Projects/brief.txt");
        assertThat(data.starred("b@x.org")).containsExactlyInAnyOrder("r:Archive/Projects/Design/logo.svg",
                "r:Projects-old/keep.txt");
    }

    @Test
    void wildcardCharactersInNamesAreMatchedLiterally() {
        data.star("a@x.org", "r", "100%_done/a.txt");
        data.star("a@x.org", "r", "100xydone/b.txt");
        data.dropStars("r", "100%_done");
        assertThat(data.starred("a@x.org")).containsExactly("r:100xydone/b.txt");
    }

    @Test
    void preferencesRoundTripAndDefaultToNothing() {
        assertThat(data.preferences("a@x.org")).isEmpty();
        final var saved = new UserData.Preferences(Workspace.Layout.GRID, Workspace.Density.COMFORTABLE,
                new Sort(SortKey.SIZE, false));
        data.savePreferences("A@x.org", saved);
        assertThat(data.preferences("a@x.org")).contains(saved);
        final var changed = new UserData.Preferences(Workspace.Layout.LIST, Workspace.Density.DENSE, Sort.DEFAULT);
        data.savePreferences("a@x.org", changed);
        assertThat(data.preferences("a@x.org")).contains(changed);
    }
}
