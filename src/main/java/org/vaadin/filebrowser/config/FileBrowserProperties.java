package org.vaadin.filebrowser.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Deployment-time settings under {@code filebrowser.*}, also settable as {@code FILEBROWSER_*} env vars. */
@ConfigurationProperties("filebrowser")
public record FileBrowserProperties(Path databasePath, Map<String, Provider> oidc, List<String> allowedDomains,
                                    List<String> allowedEmails, boolean requireVerifiedEmail,
                                    List<String> adminEmails, DataSize maxUploadSize, Path secretKeyFile,
                                    List<String> linkUploadAllowedNetworks, DataSize maxExtractSize,
                                    Integer maxExtractEntries, Integer maxFolderUploadEntries,
                                    Integer deleteUndoSeconds) {

    /** Unless set, an archive may expand to this many times the upload limit. */
    private static final int DEFAULT_EXTRACT_FACTOR = 4;
    private static final int DEFAULT_EXTRACT_ENTRIES = 10_000;
    private static final int DEFAULT_FOLDER_UPLOAD_ENTRIES = 10_000;
    private static final int DEFAULT_DELETE_UNDO_SECONDS = 5;

    public enum ProviderIcon { GOOGLE, KEYCLOAK, POCKET_ID, GENERIC }

    /** How a sign-in button looks; keyed by the Spring client registration id. */
    public record Provider(String label, ProviderIcon icon) {
    }

    // PMD reads a compact constructor's reassigned parameters as unused
    @SuppressWarnings("PMD.UnusedAssignment")
    public FileBrowserProperties {
        oidc = oidc == null ? Map.of() : Map.copyOf(oidc);
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        allowedEmails = allowedEmails == null ? List.of() : List.copyOf(allowedEmails);
        adminEmails = adminEmails == null ? List.of() : List.copyOf(adminEmails);
        linkUploadAllowedNetworks = linkUploadAllowedNetworks == null ? List.of() : List.copyOf(linkUploadAllowedNetworks);
        maxExtractEntries = Objects.requireNonNullElse(maxExtractEntries, DEFAULT_EXTRACT_ENTRIES);
        maxFolderUploadEntries = Objects.requireNonNullElse(maxFolderUploadEntries, DEFAULT_FOLDER_UPLOAD_ENTRIES);
        deleteUndoSeconds = Objects.requireNonNullElse(deleteUndoSeconds, DEFAULT_DELETE_UNDO_SECONDS);
        if (deleteUndoSeconds < 0) {
            throw new IllegalStateException("FILEBROWSER_DELETE_UNDO_SECONDS must be 0 or more: " + deleteUndoSeconds);
        }
    }

    /** The most an extracted archive may add up to: the setting, else a multiple of the upload limit. */
    public long maxExtractBytes() {
        if (maxExtractSize != null) {
            return maxExtractSize.toBytes();
        }
        return maxUploadSize == null ? Long.MAX_VALUE : maxUploadSize.toBytes() * DEFAULT_EXTRACT_FACTOR;
    }

    public boolean isAdminEmail(String email) {
        if (email == null) {
            return false;
        }
        final var address = normalizedEmail(email);
        return adminEmails.stream().anyMatch(a -> normalizedEmail(a).equals(address));
    }

    /**
     * Trimmed, with only A–Z lower-cased. Unicode case folding would match look-alikes: {@code ı}
     * equals {@code i} ignoring case, and the Kelvin sign lower-cases to {@code k}.
     */
    public static String normalizedEmail(String email) {
        final var out = new StringBuilder(email.length());
        email.trim().chars().forEach(c -> out.append((char) (c >= 'A' && c <= 'Z' ? c + 'a' - 'A' : c)));
        return out.toString();
    }

    public Provider provider(String registrationId) {
        final var configured = oidc.get(registrationId);
        final var label = configured != null && configured.label() != null ? configured.label() : capitalize(registrationId);
        final var icon = configured != null && configured.icon() != null ? configured.icon() : ProviderIcon.GENERIC;
        return new Provider(label, icon);
    }

    private static String capitalize(String id) {
        return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}
