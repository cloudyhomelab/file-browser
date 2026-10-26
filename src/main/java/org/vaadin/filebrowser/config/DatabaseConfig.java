package org.vaadin.filebrowser.config;

import java.io.IOException;
import java.nio.file.Files;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration
public class DatabaseConfig {

    /** How long a connection waits for another writer before failing with SQLITE_BUSY. */
    private static final int BUSY_TIMEOUT_MS = 5000;

    @Bean
    public DataSource dataSource(FileBrowserProperties properties) throws IOException {
        final var file = properties.databasePath().toAbsolutePath();
        Files.createDirectories(file.getParent());

        final var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);

        final var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file);
        return dataSource;
    }
}
