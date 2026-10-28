package org.vaadin.filebrowser.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MoveCheckTest {

    @Test
    void anotherFolderIsFine() {
        assertThat(MoveCheck.of(List.of("a"), Set.of(), List.of("b"))).isEqualTo(MoveCheck.OK);
        assertThat(MoveCheck.of(List.of("a"), Set.of("a/x"), List.of())).isEqualTo(MoveCheck.OK);
    }

    @Test
    void theFolderTheItemsAreInIsRefused() {
        assertThat(MoveCheck.of(List.of(), Set.of(), List.of())).isEqualTo(MoveCheck.SAME_FOLDER);
        assertThat(MoveCheck.of(List.of("a", "b"), Set.of(), List.of("a", "b"))).isEqualTo(MoveCheck.SAME_FOLDER);
    }

    @Test
    void aMovedFolderOrAnythingBelowItIsRefused() {
        assertThat(MoveCheck.of(List.of(), Set.of("a"), List.of("a"))).isEqualTo(MoveCheck.INTO_ITSELF);
        assertThat(MoveCheck.of(List.of(), Set.of("a"), List.of("a", "b", "c"))).isEqualTo(MoveCheck.INTO_ITSELF);
    }

    @Test
    void aSiblingWhoseNameStartsTheSameIsFine() {
        assertThat(MoveCheck.of(List.of(), Set.of("a"), List.of("ab"))).isEqualTo(MoveCheck.OK);
    }
}
