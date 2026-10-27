package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * How the add dialog presents a file-system type; {@code regionLabel} is null for types without a region.
 *
 * @param checkTimeout shown when the file system doesn't answer the check before it is added
 * @param checkFailed  shown when that check fails without a more specific reason
 */
public record RootTypeInfo(String displayName, String endpointLabel, String endpointPlaceholder,
                           String endpointPrefix, String endpointError, String namePlaceholder,
                           String regionLabel, String defaultRegion, String checkTimeout, String checkFailed)
        implements Serializable {
}
