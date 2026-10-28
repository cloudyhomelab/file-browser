package org.vaadin.filebrowser.config;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.vaadin.filebrowser.service.SignInPolicy;
import org.vaadin.filebrowser.service.UserAccounts;
import org.vaadin.filebrowser.web.FileStreamController;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Sign-in is delegated entirely to the configured OIDC providers; the app has no login form. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String SIGN_IN_PATH = "/sign-in";
    public static final String AUTHORIZATION_PATH = "/oauth2/authorization/";
    private static final String POST_LOGOUT_REDIRECT = "{baseUrl}";
    private static final String[] PUBLIC_RESOURCES = {"/styles.css", "/icons.svg", "/favicon.svg", "/oidc/**"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> repository,
                                                   SignInPolicy policy, FileBrowserProperties properties,
                                                   UserAccounts accounts) throws Exception {
        final var registrations = registrations(repository);
        // with a single provider the chooser would be a page with one button, so go straight to it
        final var loginPage = registrations.size() == 1
                ? AUTHORIZATION_PATH + registrations.getFirst().getRegistrationId()
                : SIGN_IN_PATH;
        final var files = PathPatternRequestMatcher.withDefaults().matcher(FileStreamController.PATH_PATTERN);
        // Vaadin denies requests outside its own routes unless a rule here allows them
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_RESOURCES).permitAll()
                .requestMatchers(files).authenticated());
        // media elements can't follow a redirect to the provider's sign-in page
        http.exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), files));
        // the provider session is ended too when it advertises end_session_endpoint (Google does not)
        http.with(VaadinSecurityConfigurer.vaadin(), vaadin -> vaadin.oauth2LoginPage(loginPage, POST_LOGOUT_REDIRECT));
        final var userService = new FileBrowserOidcUserService(policy, properties, accounts);
        // the default failure URL is loginPage?error, which with one provider would loop straight back to it
        http.oauth2Login(oauth -> oauth
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(userService))
                .failureHandler(SecurityConfig::onSignInFailure));
        return http.build();
    }

    private static void onSignInFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        final var code = exception instanceof OAuth2AuthenticationException oauth ? oauth.getError().getErrorCode() : "";
        final var encoded = URLEncoder.encode(code, StandardCharsets.UTF_8);
        response.sendRedirect(request.getContextPath() + SIGN_IN_PATH + "?error=" + encoded);
    }

    public static List<ClientRegistration> registrations(ObjectProvider<ClientRegistrationRepository> repository) {
        final var found = repository.getIfAvailable();
        final var out = new ArrayList<ClientRegistration>();
        if (found instanceof Iterable<?> iterable) {
            iterable.forEach(r -> out.add((ClientRegistration) r));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("No OIDC provider configured. Set "
                    + "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_ID / _CLIENT_SECRET and "
                    + "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_ISSUER_URI for at least one provider.");
        }
        return out;
    }
}
