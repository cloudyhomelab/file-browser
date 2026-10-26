package org.vaadin.filebrowser.data;

import java.io.Serializable;

public record RootTypeInfo(String displayName, String endpointLabel, String endpointPlaceholder,
                           String endpointPrefix, String endpointError, String namePlaceholder,
                           String regionLabel, String defaultRegion, Capacity newCapacity) implements Serializable {
}
