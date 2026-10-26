package org.vaadin.filebrowser.data;

import java.io.Serializable;

/** How the add dialog presents a file-system type; {@code regionLabel} is null for types without a region. */
public record RootTypeInfo(String displayName, String endpointLabel, String endpointPlaceholder,
                           String endpointPrefix, String endpointError, String namePlaceholder,
                           String regionLabel, String defaultRegion) implements Serializable {
}
