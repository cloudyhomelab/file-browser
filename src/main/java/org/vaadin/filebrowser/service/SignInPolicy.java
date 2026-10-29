package org.vaadin.filebrowser.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.config.FileBrowserProperties;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Decides whether an identity returned by an OIDC provider may use the app. */
@Component
public class SignInPolicy {

    @Getter
    @RequiredArgsConstructor
    public enum Refusal {
        MISSING_EMAIL("missing_email"),
        UNVERIFIED_EMAIL("unverified_email"),
        EMAIL_NOT_ALLOWED("email_not_allowed");

        private final String code;
    }

    private final Set<String> allowedDomains;
    private final Set<String> allowedEmails;
    private final boolean requireVerifiedEmail;

    @Autowired
    public SignInPolicy(FileBrowserProperties properties) {
        this(properties.allowedDomains(), properties.allowedEmails(), properties.requireVerifiedEmail());
    }

    SignInPolicy(List<String> allowedDomains, List<String> allowedEmails, boolean requireVerifiedEmail) {
        this.allowedDomains = normalized(allowedDomains);
        this.allowedEmails = normalized(allowedEmails);
        this.requireVerifiedEmail = requireVerifiedEmail;
    }

    /** @param emailVerified the {@code email_verified} claim, or null when the provider does not send it */
    public Optional<Refusal> check(String email, Boolean emailVerified) {
        if (email == null || email.isBlank()) {
            return Optional.of(Refusal.MISSING_EMAIL);
        }
        // a provider that doesn't send the claim hasn't verified the email
        if (requireVerifiedEmail && !Boolean.TRUE.equals(emailVerified)) {
            return Optional.of(Refusal.UNVERIFIED_EMAIL);
        }
        final var address = FileBrowserProperties.normalizedEmail(email);
        final var domain = address.substring(address.lastIndexOf('@') + 1);
        if (!allowedDomains.isEmpty() && !allowedDomains.contains(domain)) {
            return Optional.of(Refusal.EMAIL_NOT_ALLOWED);
        }
        if (!allowedEmails.isEmpty() && !allowedEmails.contains(address)) {
            return Optional.of(Refusal.EMAIL_NOT_ALLOWED);
        }
        return Optional.empty();
    }

    private static Set<String> normalized(List<String> values) {
        return values.stream()
                .map(FileBrowserProperties::normalizedEmail)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
