package org.vaadin.filebrowser.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;

/**
 * Downloads files from web links on the server, without letting a link reach internal services:
 * every address a host resolves to is checked against the {@link AddressPolicy}, and only checked
 * addresses are connected to, so DNS can't swap in an internal one afterwards. Redirects are
 * followed by hand and checked the same way.
 */
@Service
public class LinkFetcher implements AutoCloseable {

    /** Deliberately vague: which address or DNS answer was refused is not the user's business. */
    public static final String REFUSED = "This link can't be downloaded.";
    private static final String TOO_LARGE = "The file at this link is larger than the upload limit.";
    private static final String FALLBACK_NAME = "download";
    private static final int MAX_REDIRECTS = 5;
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(10);
    /** A transfer that sends nothing for this long is given up. */
    private static final Timeout STALL_TIMEOUT = Timeout.ofSeconds(30);
    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PARAM = Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)",
            Pattern.CASE_INSENSITIVE);

    private final CloseableHttpClient client;

    /** What a link offers, read from its headers. */
    public record LinkInfo(String url, String name, Long size) implements Serializable {
    }

    @Autowired
    public LinkFetcher(FileBrowserProperties properties) {
        this(new AddressPolicy(properties.linkUploadAllowedNetworks())::allows, SystemDefaultDnsResolver.INSTANCE);
    }

    /** @param dns resolves host names before {@code allowed} judges the answers */
    LinkFetcher(Predicate<InetAddress> allowed, DnsResolver dns) {
        final var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new CheckingResolver(dns, allowed))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(CONNECT_TIMEOUT)
                        .setSocketTimeout(STALL_TIMEOUT)
                        .build())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .setUserAgent("FileBrowser")
                .build();
    }

    /** Only answers the client may connect to; a refused one fails the whole lookup. */
    private record CheckingResolver(DnsResolver delegate, Predicate<InetAddress> allowed) implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            final var addresses = delegate.resolve(host);
            for (final var address : addresses) {
                if (!allowed.test(address)) {
                    throw new UnknownHostException("refused");
                }
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return delegate.resolveCanonicalHostname(host);
        }
    }

    /** Reads the name and size a link offers, without downloading it. */
    public LinkInfo inspect(String url) {
        try (var opened = open(url)) {
            return new LinkInfo(url, fileName(opened.response(), opened.uri()), size(opened.response()));
        } catch (IOException e) {
            throw new StorageException(REFUSED, e);
        }
    }

    /** The link's content, at most {@code maxBytes}; closing the stream ends the download. */
    public InputStream fetch(String url, long maxBytes) {
        final Opened opened;
        try {
            opened = open(url);
        } catch (IOException e) {
            throw new StorageException(REFUSED, e);
        }
        try {
            final var size = size(opened.response());
            if (size != null && size > maxBytes) {
                throw new StorageException(TOO_LARGE);
            }
            final var entity = opened.response().getEntity();
            if (entity == null) {
                throw new StorageException(REFUSED);
            }
            return new LimitedStream(entity.getContent(), maxBytes, opened);
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e instanceof StorageException storageException ? storageException : new StorageException(REFUSED, e);
        }
    }

    private record Opened(HttpGet request, ClassicHttpResponse response, URI uri) implements AutoCloseable {

        /**
         * Drops the connection. A plain close would first read the rest of the body so the
         * connection could be reused, which for a large file means downloading all of it.
         */
        @Override
        public void close() {
            request.cancel();
            try {
                response.close();
            } catch (IOException e) {
                // the connection is dropped either way
            }
        }
    }

    private Opened open(String url) throws IOException {
        var uri = check(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final var request = new HttpGet(uri);
            final var response = client.executeOpen(null, request, null);
            final var status = response.getCode();
            if (status >= 200 && status < 300) {
                return new Opened(request, response, uri);
            }
            final var location = response.getFirstHeader(HttpHeaders.LOCATION);
            new Opened(request, response, uri).close();
            if (status < 300 || status >= 400 || location == null) {
                throw new IOException("status " + status);
            }
            uri = check(uri.resolve(location.getValue()).toString());
        }
        throw new IOException("too many redirects");
    }

    /** http(s), a host and no credentials; addresses, literal ones included, are judged by the resolver. */
    private URI check(String url) throws IOException {
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IOException("invalid link", e);
        }
        final var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("unsupported link");
        }
        return uri;
    }

    private static Long size(ClassicHttpResponse response) {
        final var entity = response.getEntity();
        return entity == null || entity.getContentLength() < 0 ? null : entity.getContentLength();
    }

    /** From Content-Disposition, else the last part of the path, else a fixed name; always a valid file name. */
    static String fileName(ClassicHttpResponse response, URI uri) {
        final var disposition = response.getFirstHeader("Content-Disposition");
        final var fromHeader = disposition == null ? null : dispositionName(disposition.getValue());
        if (fromHeader != null) {
            return fromHeader;
        }
        final var path = uri.getRawPath() == null ? "" : uri.getRawPath();
        final var segment = path.substring(path.lastIndexOf('/') + 1);
        return validOr(URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8), FALLBACK_NAME);
    }

    static String dispositionName(String header) {
        final var star = FILENAME_STAR.matcher(header);
        if (star.find()) {
            final var charsetName = star.group(1).trim();
            try {
                final var charset = charsetName.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
                return validOr(URLDecoder.decode(star.group(2).trim().replace("+", "%2B"), charset), null);
            } catch (IllegalArgumentException e) {
                // also an unknown or malformed charset name
                return null;
            }
        }
        final var plain = FILENAME_PARAM.matcher(header);
        if (plain.find()) {
            final var value = plain.group(2) != null ? plain.group(2) : plain.group(1).trim();
            return validOr(value, null);
        }
        return null;
    }

    private static String validOr(String candidate, String fallback) {
        if (candidate == null) {
            return fallback;
        }
        // a server may suggest a path; only its last part is a name
        final var name = candidate.substring(Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\')) + 1).trim();
        try {
            StoragePaths.requireValidName(name);
            return name;
        } catch (StorageException e) {
            return fallback;
        }
    }

    /** More than the upload limit arrived, whatever the server announced. */
    public static final class TooLargeException extends IOException {

        TooLargeException() {
            super(TOO_LARGE);
        }
    }

    /** Stops with an error once more than the limit arrives, whatever the server announced. */
    private static final class LimitedStream extends FilterInputStream {

        private final long maxBytes;
        private final Opened opened;
        private long bytesRead;

        LimitedStream(InputStream in, long maxBytes, Opened opened) {
            super(in);
            this.maxBytes = maxBytes;
            this.opened = opened;
        }

        @Override
        public int read() throws IOException {
            final var value = super.read();
            if (value >= 0) {
                countBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            final var count = super.read(buffer, offset, length);
            if (count > 0) {
                countBytes(count);
            }
            return count;
        }

        private void countBytes(int bytes) throws IOException {
            bytesRead += bytes;
            if (bytesRead > maxBytes) {
                throw new TooLargeException();
            }
        }

        @Override
        public void close() {
            opened.close();
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
