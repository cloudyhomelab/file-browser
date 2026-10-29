package org.vaadin.filebrowser.storage;

import lombok.Getter;

/**
 * The server answered with a key or certificate nobody has confirmed yet. Thrown only while no
 * fingerprint is pinned, so an admin can be shown {@link #getFingerprint()} before trusting it.
 */
public class UntrustedServerException extends StorageException {

    @Getter
    private final String fingerprint;

    public UntrustedServerException(String fingerprint) {
        super("Check the server's fingerprint before connecting.");
        this.fingerprint = fingerprint;
    }
}
