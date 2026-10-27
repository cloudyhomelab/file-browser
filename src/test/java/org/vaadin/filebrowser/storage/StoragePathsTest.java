package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoragePathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", ".hidden", "with space", "ünïcode", "a..b"})
    void acceptsOrdinaryNames(String name) {
        assertThatCode(() -> StoragePaths.requireValidName(name)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "a/b", "a\\b", "a\rb", "a\nb", "a\tb", "a\u007fb", "a\0b", "\u001b[1m"})
    void refusesNamesNoStorageAccepts(String name) {
        assertThatThrownBy(() -> StoragePaths.requireValidName(name)).isInstanceOf(StorageException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "a/b", "Projects/Design/logo.txt"})
    void acceptsRelativePaths(String path) {
        assertThatCode(() -> StoragePaths.requireValidPath(path)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a", "a/", "a//b", "../x", "a/./b", "a/..", "a/x\ny", "a/x\r\nDELE y"})
    void refusesPathsWithBadSegments(String path) {
        assertThatThrownBy(() -> StoragePaths.requireValidPath(path)).isInstanceOf(StorageException.class);
    }

    @Test
    void refusesNull() {
        assertThatThrownBy(() -> StoragePaths.requireValidPath(null)).isInstanceOf(StorageException.class);
    }
}
