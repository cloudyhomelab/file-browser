package org.vaadin.filebrowser.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

/** Sign-in is delegated entirely to the configured OIDC providers; the app has no login form. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String SIGN_IN_PATH = "/sign-in";
    public static final String AUTHORIZATION_PATH = "/oauth2/authorization/";
    private static final String[] PUBLIC_RESOURCES = {"/styles.css", "/icons.svg", "/favicon.svg", "/oidc/**"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> repository)
            throws Exception {
        final var registrations = registrations(repository);
        // with a single provider the chooser would be a page with one button, so go straight to it
        final var loginPage = registrations.size() == 1
                ? AUTHORIZATION_PATH + registrations.getFirst().getRegistrationId()
                : SIGN_IN_PATH;
        http.authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_RESOURCES).permitAll());
        http.with(VaadinSecurityConfigurer.vaadin(), vaadin -> vaadin.oauth2LoginPage(loginPage));
        // the default failure URL is loginPage?error, which with one provider would loop straight back to it
        http.oauth2Login(oauth -> oauth.failureUrl(SIGN_IN_PATH + "?error"));
        return http.build();
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
