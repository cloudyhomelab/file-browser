package org.vaadin.filebrowser.storage.remote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoteLocationTest {

    @Test
    void readsHostPortAndFolder() {
        assertThat(RemoteLocation.parse("sftp://files.example.com/srv/projects/", "sftp", 22))
                .isEqualTo(new RemoteLocation("files.example.com", 22, "/srv/projects"));
        assertThat(RemoteLocation.parse("ftp://10.0.0.5:2121", "ftp", 21))
                .isEqualTo(new RemoteLocation("10.0.0.5", 2121, "/"));
        assertThat(RemoteLocation.parse("ftp://host/My%20Files", "ftp", 21).folder()).isEqualTo("/My Files");
    }

    @Test
    void refusesOtherSchemesUserNamesAndOddPaths() {
        assertThat(RemoteLocation.parse("ftp://host/x", "sftp", 22)).isNull();
        assertThat(RemoteLocation.parse("sftp://alice@host/x", "sftp", 22)).isNull();
        assertThat(RemoteLocation.parse("sftp:///x", "sftp", 22)).isNull();
        assertThat(RemoteLocation.parse("sftp://host/a//b", "sftp", 22)).isNull();
        assertThat(RemoteLocation.parse("sftp://host/x?y", "sftp", 22)).isNull();
        assertThat(RemoteLocation.parse("sftp://host:99999/x", "sftp", 22)).isNull();
    }

    @Test
    void resolvesPathsInsideTheFolder() {
        assertThat(new RemoteLocation("h", 22, "/").resolve("a/b")).isEqualTo("/a/b");
        assertThat(new RemoteLocation("h", 22, "/srv").resolve("a/b")).isEqualTo("/srv/a/b");
        assertThat(new RemoteLocation("h", 22, "/srv").resolve("")).isEqualTo("/srv");
    }
}
