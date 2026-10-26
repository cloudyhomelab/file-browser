package org.vaadin.filebrowser.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Everyone who has signed in, keyed by lower-cased email. Plan 03 builds admin management on this. */
@Repository
public class UserAccounts {

    public record Account(String email, String name, String lastProvider, Instant firstSignIn, Instant lastSignIn) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public UserAccounts(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    UserAccounts(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void recordSignIn(String email, String name, String provider) {
        final var now = clock.instant().toString();
        jdbc.sql("""
                        INSERT INTO user_account (email, name, last_provider, first_sign_in, last_sign_in)
                        VALUES (:email, :name, :provider, :now, :now)
                        ON CONFLICT (email) DO UPDATE SET
                            name = excluded.name,
                            last_provider = excluded.last_provider,
                            last_sign_in = excluded.last_sign_in
                        """)
                .param("email", normalize(email))
                .param("name", name)
                .param("provider", provider)
                .param("now", now)
                .update();
    }

    public Optional<Account> find(String email) {
        return jdbc.sql("SELECT * FROM user_account WHERE email = :email")
                .param("email", normalize(email))
                .query(UserAccounts::map)
                .optional();
    }

    public List<Account> all() {
        return jdbc.sql("SELECT * FROM user_account ORDER BY email").query(UserAccounts::map).list();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        final var first = Instant.parse(rs.getString("first_sign_in"));
        final var last = Instant.parse(rs.getString("last_sign_in"));
        return new Account(rs.getString("email"), rs.getString("name"), rs.getString("last_provider"), first, last);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
