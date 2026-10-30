package org.vaadin.filebrowser.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class FileBrowserPropertiesTest {

    private static FileBrowserProperties withAdmins(List<String> admins) {
        return new FileBrowserProperties(null, null, null, null, true, admins, null, null, null, null, null, null, null);
    }

    @Test
    void adminEmailsMatchCaseInsensitivelyAndIgnoreSpaces() {
        final var properties = withAdmins(List.of(" Admin@Example.com "));
        assertThat(properties.isAdminEmail("admin@example.COM")).isTrue();
        assertThat(properties.isAdminEmail("other@example.com")).isFalse();
        assertThat(properties.isAdminEmail(null)).isFalse();
    }

    @Test
    void lookAlikeAdminEmailsDoNotMatch() {
        final var properties = withAdmins(List.of("admin@example.com", "kim@example.com"));
        // dotless ı equals i ignoring case; the Kelvin sign lower-cases to k
        assertThat(properties.isAdminEmail("adm\u0131n@example.com")).isFalse();
        assertThat(properties.isAdminEmail("\u212Aim@example.com")).isFalse();
    }

    @Test
    void noAdminsWhenUnset() {
        final var properties = withAdmins(null);
        assertThat(properties.isAdminEmail("admin@example.com")).isFalse();
    }

    @Test
    void deletesWaitFiveSecondsUnlessSetAndNeverANegativeTime() {
        assertThat(withAdmins(null).deleteUndoSeconds()).isEqualTo(5);
        assertThatThrownBy(() -> new FileBrowserProperties(null, null, null, null, true, null, null, null, null, null,
                null, null, -1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FILEBROWSER_DELETE_UNDO_SECONDS must be 0 or more: -1");
    }
}
