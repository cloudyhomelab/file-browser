package org.vaadin.filebrowser.config;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.vaadin.filebrowser.service.SignInPolicy;

/** Loads the user like Spring's default service, then refuses identities the sign-in policy rejects. */
public class PolicyCheckingOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final SignInPolicy policy;

    public PolicyCheckingOidcUserService(SignInPolicy policy) {
        this.policy = policy;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        final var user = delegate.loadUser(request);
        final var refusal = policy.check(user.getEmail(), user.getEmailVerified());
        if (refusal.isPresent()) {
            final var error = new OAuth2Error(refusal.get().code());
            throw new OAuth2AuthenticationException(error);
        }
        return user;
    }
}
