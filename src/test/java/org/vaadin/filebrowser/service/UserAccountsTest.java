package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

class UserAccountsTest {

    private static final Instant FIRST = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-27T09:30:00Z");

    @TempDir
    Path dir;

    private JdbcClient jdbc;

    @BeforeEach
    void migrate() {
        final var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        final var flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    private UserAccounts at(Instant instant) {
        final var clock = Clock.fixed(instant, ZoneOffset.UTC);
        return new UserAccounts(jdbc, clock);
    }

    @Test
    void firstSignInCreatesTheAccount() {
        at(FIRST).recordSignIn("Alice@Example.com", "Alice", "mock");
        final var account = at(FIRST).find("alice@example.com").orElseThrow();
        assertThat(account.email()).isEqualTo("alice@example.com");
        assertThat(account.name()).isEqualTo("Alice");
        assertThat(account.lastProvider()).isEqualTo("mock");
        assertThat(account.firstSignIn()).isEqualTo(FIRST);
        assertThat(account.lastSignIn()).isEqualTo(FIRST);
    }

    @Test
    void laterSignInsUpdateNameProviderAndTimeButKeepTheFirstSignIn() {
        at(FIRST).recordSignIn("alice@example.com", "Alice", "mock");
        at(SECOND).recordSignIn("ALICE@example.com", "Alice Example", "pocket-id");
        final var accounts = at(SECOND).all();
        assertThat(accounts).hasSize(1);
        final var account = accounts.getFirst();
        assertThat(account.name()).isEqualTo("Alice Example");
        assertThat(account.lastProvider()).isEqualTo("pocket-id");
        assertThat(account.firstSignIn()).isEqualTo(FIRST);
        assertThat(account.lastSignIn()).isEqualTo(SECOND);
    }
}
