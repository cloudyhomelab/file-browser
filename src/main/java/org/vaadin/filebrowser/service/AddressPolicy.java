package org.vaadin.filebrowser.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Which addresses the server may download links from: public internet addresses always; internal
 * ones only inside networks a deployment allows, and those must be private ranges (RFC 1918 or
 * IPv6 unique-local). Loopback, link-local (incl. cloud metadata) and other special ranges never.
 */
public final class AddressPolicy {

    /** A block of addresses, e.g. {@code 10.0.0.0/8}. */
    record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            final var slash = text.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException(text);
            }
            final byte[] address;
            try {
                // only literal addresses are parsed here, never host names
                address = InetAddress.getByName(text.substring(0, slash).trim()).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(text, e);
            }
            final var prefix = Integer.parseInt(text.substring(slash + 1).trim());
            if (prefix < 0 || prefix > address.length * Byte.SIZE) {
                throw new IllegalArgumentException(text);
            }
            return new Cidr(address, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                final var index = bit / Byte.SIZE;
                final var mask = 1 << (Byte.SIZE - 1 - bit % Byte.SIZE);
                if ((address[index] & mask) != (network[index] & mask)) {
                    return false;
                }
            }
            return true;
        }

        boolean within(Cidr outer) {
            return prefix >= outer.prefix && outer.contains(network);
        }
    }

    /** Ranges a deployment may open up: private networks only. */
    private static final List<Cidr> PRIVATE = cidrs("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");

    /** Everything that isn't the public internet. */
    private static final List<Cidr> NOT_PUBLIC = cidrs(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24",
            "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
            "::/128", "::1/128", "fe80::/10", "fc00::/7", "ff00::/8", "2001:db8::/32",
            // these carry an IPv4 address inside that could point anywhere
            "64:ff9b::/96", "2002::/16");

    private final List<Cidr> allowed;

    /** @param allowedNetworks CIDRs inside private ranges; anything else stops the app from starting */
    public AddressPolicy(List<String> allowedNetworks) {
        this.allowed = allowedNetworks.stream().map(AddressPolicy::allowedNetwork).toList();
    }

    private static Cidr allowedNetwork(String text) {
        final Cidr cidr;
        try {
            cidr = Cidr.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("FILEBROWSER_LINK_UPLOAD_ALLOWED_NETWORKS has an invalid CIDR: " + text, e);
        }
        if (PRIVATE.stream().noneMatch(cidr::within)) {
            throw new IllegalStateException("FILEBROWSER_LINK_UPLOAD_ALLOWED_NETWORKS may only open private ranges "
                    + "(10/8, 172.16/12, 192.168/16, fc00::/7): " + text);
        }
        return cidr;
    }

    private static List<Cidr> cidrs(String... texts) {
        return Arrays.stream(texts).map(Cidr::parse).toList();
    }

    public boolean allows(InetAddress address) {
        final var bytes = unwrap(address);
        if (NOT_PUBLIC.stream().noneMatch(c -> c.contains(bytes))) {
            return true;
        }
        return allowed.stream().anyMatch(c -> c.contains(bytes));
    }

    /** IPv4-mapped ({@code ::ffff:a.b.c.d}) and -compatible IPv6 addresses are judged as the IPv4 inside. */
    private static byte[] unwrap(InetAddress address) {
        final var bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length != 16) {
            return bytes;
        }
        final var mapped = bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        final var compatible = bytes[10] == 0 && bytes[11] == 0
                && !(bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0 && (bytes[15] == 0 || bytes[15] == 1));
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return bytes;
            }
        }
        if (address instanceof Inet6Address && (mapped || compatible)) {
            return Arrays.copyOfRange(bytes, 12, 16);
        }
        return bytes;
    }
}
