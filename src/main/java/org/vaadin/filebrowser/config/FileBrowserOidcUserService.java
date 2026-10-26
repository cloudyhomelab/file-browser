package org.vaadin.filebrowser.config;

import java.util.HashSet;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.vaadin.filebrowser.service.SignInPolicy;
import org.vaadin.filebrowser.service.UserAccounts;

/**
 * Loads the user like Spring's default service, refuses identities the sign-in policy rejects, and
 * grants the admin role to the emails named in {@code filebrowser.admin-emails}.
 */
public class FileBrowserOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final SignInPolicy policy;
    private final FileBrowserProperties properties;
    private final UserAccounts accounts;

    public FileBrowserOidcUserService(SignInPolicy policy, FileBrowserProperties properties, UserAccounts accounts) {
        this.policy = policy;
        this.properties = properties;
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        final var user = delegate.loadUser(request);
        final var refusal = policy.check(user.getEmail(), user.getEmailVerified());
        if (refusal.isPresent()) {
            final var error = new OAuth2Error(refusal.get().code());
            throw new OAuth2AuthenticationException(error);
        }
        final var provider = request.getClientRegistration().getRegistrationId();
        accounts.recordSignIn(user.getEmail(), displayName(user), provider);
        if (!properties.isAdminEmail(user.getEmail())) {
            return user;
        }
        final var authorities = new HashSet<GrantedAuthority>(user.getAuthorities());
        final var adminRole = new SimpleGrantedAuthority(Roles.ADMIN_AUTHORITY);
        authorities.add(adminRole);
        final var nameAttribute = request.getClientRegistration().getProviderDetails().getUserInfoEndpoint()
                .getUserNameAttributeName();
        return nameAttribute == null || nameAttribute.isBlank()
                ? new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo())
                : new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameAttribute);
    }

    private static String displayName(OidcUser user) {
        for (final var candidate : new String[]{user.getFullName(), user.getPreferredUsername(), user.getEmail()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return user.getSubject();
    }
}
