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
        // the contract allows no user at all, which leaves no email to sign in with
        if (user == null) {
            throw refused(SignInPolicy.Refusal.MISSING_EMAIL);
        }
        final var email = user.getEmail();
        final var refusal = policy.check(email, user.getEmailVerified());
        // the policy refuses a missing email; the null check only makes that visible here
        if (email == null || refusal.isPresent()) {
            throw refused(refusal.orElse(SignInPolicy.Refusal.MISSING_EMAIL));
        }
        final var provider = request.getClientRegistration().getRegistrationId();
        accounts.recordSignIn(email, displayName(user), provider);
        if (!properties.isAdminEmail(email)) {
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

    private static OAuth2AuthenticationException refused(SignInPolicy.Refusal refusal) {
        final var error = new OAuth2Error(refusal.code());
        return new OAuth2AuthenticationException(error);
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
