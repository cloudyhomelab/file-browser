package org.vaadin.filebrowser.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-time settings under {@code filebrowser.*}, also settable as {@code FILEBROWSER_*} env vars. */
@ConfigurationProperties("filebrowser")
public record FileBrowserProperties(Path databasePath, Map<String, Provider> oidc, List<String> allowedDomains,
                                    List<String> allowedEmails, boolean requireVerifiedEmail) {

    public enum ProviderIcon { GOOGLE, KEYCLOAK, POCKET_ID, GENERIC }

    /** How a sign-in button looks; keyed by the Spring client registration id. */
    public record Provider(String label, ProviderIcon icon) {
    }

    public FileBrowserProperties {
        oidc = oidc == null ? Map.of() : Map.copyOf(oidc);
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        allowedEmails = allowedEmails == null ? List.of() : List.copyOf(allowedEmails);
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
