package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.service.SignInPolicy.Refusal;

class SignInPolicyTest {

    private static SignInPolicy policy(List<String> domains, List<String> emails, boolean requireVerified) {
        return new SignInPolicy(domains, emails, requireVerified);
    }

    @Test
    void trustsProviderWhenNoListsAreSet() {
        final var open = policy(List.of(), List.of(), true);
        assertThat(open.check("anyone@anywhere.org", true)).isEmpty();
    }

    @Test
    void refusesMissingEmail() {
        final var open = policy(List.of(), List.of(), true);
        assertThat(open.check(null, true)).contains(Refusal.MISSING_EMAIL);
        assertThat(open.check(" ", true)).contains(Refusal.MISSING_EMAIL);
    }

    @Test
    void refusesUnverifiedOrUnstatedEmailOnlyWhenRequired() {
        assertThat(policy(List.of(), List.of(), true).check("a@b.org", false)).contains(Refusal.UNVERIFIED_EMAIL);
        assertThat(policy(List.of(), List.of(), true).check("a@b.org", null)).contains(Refusal.UNVERIFIED_EMAIL);
        assertThat(policy(List.of(), List.of(), false).check("a@b.org", false)).isEmpty();
        assertThat(policy(List.of(), List.of(), false).check("a@b.org", null)).isEmpty();
    }

    @Test
    void domainListMustMatchCaseInsensitively() {
        final var domains = policy(List.of("Example.com"), List.of(), true);
        assertThat(domains.check("Alice@EXAMPLE.com", true)).isEmpty();
        assertThat(domains.check("alice@other.org", true)).contains(Refusal.EMAIL_NOT_ALLOWED);
    }

    @Test
    void emailListMustMatchCaseInsensitively() {
        final var emails = policy(List.of(), List.of(" alice@example.com "), true);
        assertThat(emails.check("ALICE@example.com", true)).isEmpty();
        assertThat(emails.check("bob@example.com", true)).contains(Refusal.EMAIL_NOT_ALLOWED);
    }

    @Test
    void bothListsMustPassWhenBothAreSet() {
        final var both = policy(List.of("example.com"), List.of("alice@example.com", "carol@other.org"), true);
        assertThat(both.check("alice@example.com", true)).isEmpty();
        assertThat(both.check("bob@example.com", true)).contains(Refusal.EMAIL_NOT_ALLOWED);
        assertThat(both.check("carol@other.org", true)).contains(Refusal.EMAIL_NOT_ALLOWED);
    }

    @Test
    void allowListsDoNotMatchLookAlikes() {
        final var emails = policy(List.of(), List.of("kim@example.com"), true);
        assertThat(emails.check("\u212Aim@example.com", true)).contains(Refusal.EMAIL_NOT_ALLOWED);
        final var domains = policy(List.of("kexample.com"), List.of(), true);
        assertThat(domains.check("a@\u212Aexample.com", true)).contains(Refusal.EMAIL_NOT_ALLOWED);
    }

    @Test
    void unverifiedIsReportedBeforeAllowList() {
        final var both = policy(List.of("example.com"), List.of(), true);
        assertThat(both.check("x@other.org", false)).isEqualTo(Optional.of(Refusal.UNVERIFIED_EMAIL));
    }
}
