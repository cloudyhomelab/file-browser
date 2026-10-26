package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;

class AddressPolicyTest {

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void allowsOnlyPublicAddressesByDefault() throws UnknownHostException {
        final var policy = new AddressPolicy(List.of());
        assertThat(policy.allows(ip("93.184.216.34"))).isTrue();
        assertThat(policy.allows(ip("2606:4700:4700::1111"))).isTrue();
        for (final var internal : List.of("127.0.0.1", "10.1.2.3", "172.20.0.5", "192.168.1.10", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fd12:3456::1", "ff02::1",
                "::ffff:10.1.2.3", "::ffff:127.0.0.1", "64:ff9b::a01:203", "2002:a01:203::1")) {
            assertThat(policy.allows(ip(internal))).as(internal).isFalse();
        }
    }

    @Test
    void allowedNetworksOpenOnlyThoseInternalAddresses() throws UnknownHostException {
        final var policy = new AddressPolicy(List.of("10.1.0.0/16", "fd00:1::/32"));
        assertThat(policy.allows(ip("10.1.2.3"))).isTrue();
        assertThat(policy.allows(ip("::ffff:10.1.2.3"))).isTrue();
        assertThat(policy.allows(ip("fd00:1::5"))).isTrue();
        assertThat(policy.allows(ip("10.2.0.1"))).isFalse();
        assertThat(policy.allows(ip("127.0.0.1"))).isFalse();
        assertThat(policy.allows(ip("169.254.169.254"))).isFalse();
    }

    @Test
    void onlyPrivateRangesMayBeAllowed() {
        for (final var bad : List.of("127.0.0.0/8", "169.254.169.254/32", "100.64.0.0/10", "0.0.0.0/0",
                "10.0.0.0/7", "8.8.8.0/24", "::1/128", "fe80::/10", "not-a-cidr", "10.0.0.1")) {
            assertThatThrownBy(() -> new AddressPolicy(List.of(bad))).as(bad).isInstanceOf(IllegalStateException.class);
        }
    }
}
