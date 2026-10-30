package org.vaadin.filebrowser.data;

import java.io.Serializable;
import java.util.Map;

/**
 * How the add dialog presents a file-system type.
 *
 * @param checkTimeout shown when the file system doesn't answer the check before it is added
 * @param checkFailed  shown when that check fails without a more specific reason
 * @param form         texts and defaults only the type's own form uses, by key; may be absent
 */
public record RootTypeInfo(String displayName, String endpointLabel, String endpointPlaceholder,
                           String endpointPrefix, String endpointError, String namePlaceholder,
                           String checkTimeout, String checkFailed, Map<String, String> form)
        implements Serializable {

    public RootTypeInfo {
        form = form == null ? Map.of() : Map.copyOf(form);
    }

    /** @throws IllegalStateException when {@code data/root-types.json} doesn't have it, which is a bug in the data */
    public String form(String key) {
        final var value = form.get(key);
        if (value == null) {
            throw new IllegalStateException("data/root-types.json has no form." + key + " for " + displayName);
        }
        return value;
    }
}
