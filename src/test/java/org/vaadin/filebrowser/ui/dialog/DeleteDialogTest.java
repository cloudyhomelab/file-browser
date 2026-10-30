package org.vaadin.filebrowser.ui.dialog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.storage.StorageEntry;

class DeleteDialogTest {

    private static StorageEntry file(String name) {
        return new StorageEntry(name, name, false, "txt", 1L, null, null, null);
    }

    private static StorageEntry folder(String name) {
        return new StorageEntry(name, name, true, StorageEntry.FOLDER_TYPE, null, null, null, 0);
    }

    @Test
    void filesAloneNeedNoTyping() {
        final var files = List.of(file("a.txt"), file("b.txt"), file("c.txt"));
        assertThat(DeleteDialog.typed(files)).isFalse();
    }

    @Test
    void aFolderEvenAnEmptyOneIsConfirmedByItsName() {
        final var empty = List.of(folder("Old stuff"));

        assertThat(DeleteDialog.typed(empty)).isTrue();
        assertThat(DeleteDialog.confirms(empty, "Old stuff ")).isTrue();
        assertThat(DeleteDialog.confirms(empty, "old stuff")).isFalse();
        assertThat(DeleteDialog.confirms(empty, "1")).isFalse();
    }

    @Test
    void severalItemsWithAFolderAreConfirmedByAPhraseWithTheirCount() {
        final var mixed = List.of(file("a.txt"), folder("Docs"), file("b.txt"));

        assertThat(DeleteDialog.typed(mixed)).isTrue();
        assertThat(DeleteDialog.confirms(mixed, "Delete 3 items")).isTrue();
        assertThat(DeleteDialog.confirms(mixed, "delete 3 items ")).isTrue();
        assertThat(DeleteDialog.confirms(mixed, "3")).isFalse();
        assertThat(DeleteDialog.confirms(mixed, "Delete 2 items")).isFalse();
    }
}
