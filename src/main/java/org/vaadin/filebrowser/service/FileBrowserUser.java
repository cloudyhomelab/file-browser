package org.vaadin.filebrowser.service;

import java.util.Collection;
import java.util.Objects;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.vaadin.filebrowser.config.Roles;

/** The signed-in user, as described by the OIDC provider they signed in with; worked out once at sign-in. */
public class FileBrowserUser extends DefaultOidcUser {

    private final String displayName;
    private final String providerLabel;

    /** @param nameAttribute the claim {@link #getName()} reads; null or blank for the subject */
    public FileBrowserUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken, OidcUserInfo userInfo,
                           String nameAttribute, String providerLabel) {
        super(authorities, idToken, userInfo, nameAttribute == null || nameAttribute.isBlank() ? IdTokenClaimNames.SUB : nameAttribute);
        this.displayName = firstPresent(getFullName(), getPreferredUsername(), getEmail(), getSubject());
        this.providerLabel = providerLabel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getProviderLabel() {
        return providerLabel;
    }

    public boolean isAdmin() {
        return getAuthorities().stream().anyMatch(a -> Roles.ADMIN_AUTHORITY.equals(a.getAuthority()));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof FileBrowserUser other && super.equals(other) && Objects.equals(providerLabel, other.providerLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), providerLabel);
    }

    private static String firstPresent(String... values) {
        for (final var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
