package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.data.Catalog;

class DiskStorageTypeTest {

    private final DiskStorageType type = new DiskStorageType(new Catalog());

    private static Optional<MountInfo> mount(String kind) {
        return Optional.of(new MountInfo(kind, "fs", "10.0.4.5:/exports/team"));
    }

    @Test
    void showsTheKindTheMountWasDetectedAs() {
        assertThat(type.kindKey(Map.of(), mount("nfs"))).isEqualTo("nfs");
        assertThat(type.kindKey(Map.of(), mount("smb"))).isEqualTo("smb");
        assertThat(type.kindKey(Map.of(), mount("other"))).isEqualTo("other");
        assertThat(type.kindKey(Map.of(), mount("disk"))).isEqualTo("disk");
        assertThat(type.kindKey(Map.of(), Optional.empty())).isEqualTo("disk");
    }

    @Test
    void onlyNetworkSharesShowTheirSource() {
        assertThat(type.detail(Map.of(), mount("nfs"))).isEqualTo("10.0.4.5:/exports/team");
        assertThat(type.detail(Map.of(), mount("smb"))).isEqualTo("10.0.4.5:/exports/team");
        assertThat(type.detail(Map.of(), mount("other"))).isEmpty();
        assertThat(type.detail(Map.of(), mount("disk"))).isEmpty();
        assertThat(type.detail(Map.of(), Optional.empty())).isEmpty();
    }
}
