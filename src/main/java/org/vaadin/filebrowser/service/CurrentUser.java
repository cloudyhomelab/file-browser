package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.Optional;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.vaadin.filebrowser.config.FileBrowserProperties;

import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.security.AuthenticationContext;

/** The signed-in user, as described by the OIDC provider they signed in with. */
@SpringComponent
public class CurrentUser implements Serializable {

    public record User(String email, String name, String providerLabel) implements Serializable {
    }

    private final AuthenticationContext authenticationContext;
    private final FileBrowserProperties properties;

    public CurrentUser(AuthenticationContext authenticationContext, FileBrowserProperties properties) {
        this.authenticationContext = authenticationContext;
        this.properties = properties;
    }

    public Optional<User> get() {
        final var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken token) || !(token.getPrincipal() instanceof OidcUser oidc)) {
            return Optional.empty();
        }
        final var email = oidc.getEmail();
        final var name = firstPresent(oidc.getFullName(), oidc.getPreferredUsername(), email, oidc.getSubject());
        final var provider = properties.provider(token.getAuthorizedClientRegistrationId());
        return Optional.of(new User(email, name, provider.label()));
    }

    public void signOut() {
        authenticationContext.logout();
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
