package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.storage.StorageException;

import com.sun.net.httpserver.HttpServer;

/**
 * Serves on every local address, so a link that slipped past the checks would really be answered.
 * Only 127.0.0.1 counts as allowed here, standing in for "public"; ::1 stands in for internal. Both are
 * loopback addresses on every system, unlike 127.0.0.2, which macOS doesn't route.
 */
class LinkFetcherTest {

    private static final InetAddress ALLOWED = literal("127.0.0.1");
    private static final InetAddress INTERNAL = literal("::1");

    private HttpServer server;
    private LinkFetcher fetcher;
    private int port;

    private static InetAddress literal(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** files.test is the allowed server, internal.test an internal one; literal addresses resolve as themselves. */
    private static final DnsResolver TEST_DNS = new DnsResolver() {
        private final Map<String, InetAddress> hosts = Map.of("files.test", ALLOWED, "internal.test", INTERNAL);

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            final var known = hosts.get(host);
            return known != null ? new InetAddress[]{known} : InetAddress.getAllByName(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    };

    private void serve(String path, int status, Map<String, String> headers, byte[] body) {
        server.createContext(path, exchange -> {
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
    }

    private String url(String host, String path) {
        return "http://" + host + ":" + port + path;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        serve("/files/report.pdf", 200, Map.of(), "report".getBytes(StandardCharsets.UTF_8));
        serve("/download.php", 200, Map.of("Content-Disposition", "attachment; filename=\"q3 numbers.xlsx\""),
                "sheet".getBytes(StandardCharsets.UTF_8));
        serve("/encoded", 200, Map.of("Content-Disposition", "attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.txt"),
                "cv".getBytes(StandardCharsets.UTF_8));
        serve("/sneaky", 200, Map.of("Content-Disposition", "attachment; filename=\"../../etc/passwd\""),
                "x".getBytes(StandardCharsets.UTF_8));
        serve("/", 200, Map.of(), "index".getBytes(StandardCharsets.UTF_8));
        final var big = new byte[1024];
        Arrays.fill(big, (byte) 'x');
        serve("/big.bin", 200, Map.of(), big);
        // announces a large file, then sends it slowly: looking it up must not wait for the body
        server.createContext("/huge.iso", exchange -> {
            exchange.sendResponseHeaders(200, 6_000_000_000L);
            try {
                exchange.getResponseBody().write(new byte[64 * 1024]);
                exchange.getResponseBody().flush();
                Thread.sleep(30_000);
            } catch (IOException | InterruptedException e) {
                // the client hung up, as it should
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        fetcher = new LinkFetcher(ALLOWED::equals, TEST_DNS);
        // redirects need the port, so they're added once it's known
        serve("/to-allowed", 302, Map.of("Location", url("files.test", "/files/report.pdf")), new byte[0]);
        serve("/to-internal", 302, Map.of("Location", url("internal.test", "/files/report.pdf")), new byte[0]);
        serve("/to-literal", 302, Map.of("Location", url("[::1]", "/files/report.pdf")), new byte[0]);
        serve("/loop", 302, Map.of("Location", "/loop"), new byte[0]);
    }

    @AfterEach
    void stop() throws IOException {
        server.stop(0);
        fetcher.close();
    }

    @Test
    void namesComeFromTheHeaderElseThePathElseAFallback() {
        final var report = fetcher.inspect(url("files.test", "/files/report.pdf"));
        assertThat(report.name()).isEqualTo("report.pdf");
        assertThat(report.size()).isEqualTo(6L);
        assertThat(fetcher.inspect(url("files.test", "/download.php?id=42")).name()).isEqualTo("q3 numbers.xlsx");
        assertThat(fetcher.inspect(url("files.test", "/encoded")).name()).isEqualTo("résumé.txt");
        assertThat(fetcher.inspect(url("files.test", "/sneaky")).name()).isEqualTo("passwd");
        assertThat(fetcher.inspect(url("files.test", "/")).name()).isEqualTo("download");
    }

    @Test
    void lookingUpALargeFileReadsOnlyItsHeaders() {
        final var started = System.nanoTime();
        final var info = fetcher.inspect(url("files.test", "/huge.iso"));
        assertThat(info.name()).isEqualTo("huge.iso");
        assertThat(info.size()).isEqualTo(6_000_000_000L);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
    }

    @Test
    void fetchesTheContentWithinTheLimit() throws IOException {
        try (final var in = fetcher.fetch(url("files.test", "/files/report.pdf"), 100)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("report");
        }
        assertThatThrownBy(() -> fetcher.fetch(url("files.test", "/big.bin"), 100))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("larger than the upload limit");
    }

    @Test
    void followsRedirectsOnlyToAllowedAddresses() {
        assertThat(fetcher.inspect(url("files.test", "/to-allowed")).name()).isEqualTo("report.pdf");
        for (final var path : new String[]{"/to-internal", "/to-literal", "/loop"}) {
            assertThatThrownBy(() -> fetcher.inspect(url("files.test", path))).as(path)
                    .isInstanceOf(StorageException.class)
                    .hasMessage(LinkFetcher.REFUSED);
        }
    }

    @Test
    void theInternalAddressReallyAnswersWhenNothingIsChecked() throws IOException {
        // the control for the refusals below: they fail because of the checks, not the network
        try (final var open = new LinkFetcher(address -> true, TEST_DNS)) {
            assertThat(open.inspect(url("[::1]", "/files/report.pdf")).name()).isEqualTo("report.pdf");
        }
    }

    @Test
    void refusesInternalHostsLiteralAddressesAndOddLinks() {
        for (final var link : new String[]{url("internal.test", "/files/report.pdf"),
                url("[::1]", "/files/report.pdf"), url("[::ffff:127.0.0.2]", "/files/report.pdf"),
                "ftp://files.test/report.pdf", "file:///etc/passwd", "http://user:secret@files.test:" + port + "/",
                "not a link", "http:///nohost"}) {
            assertThatThrownBy(() -> fetcher.inspect(link)).as(link)
                    .isInstanceOf(StorageException.class)
                    .hasMessage(LinkFetcher.REFUSED);
        }
    }
}
