package org.vaadin.filebrowser.storage.remote;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where a file system on a remote server lives: {@code scheme://host[:port]/folder}. The folder is
 * an absolute path on the server; {@code /} when the endpoint names none.
 */
public record RemoteLocation(String host, int port, String folder) {

    private static final int MAX_PORT = 65535;

    /** @return null when {@code endpoint} isn't of that form */
    public static RemoteLocation parse(String endpoint, String scheme, int defaultPort) {
        if (endpoint == null || !endpoint.startsWith(scheme + "://")) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            return null;
        }
        // user names don't belong in the endpoint, which is shown to everyone
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() > MAX_PORT) {
            return null;
        }
        final var path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        if (path.contains("//")) {
            return null;
        }
        return new RemoteLocation(uri.getHost(), uri.getPort() < 0 ? defaultPort : uri.getPort(),
                path.isEmpty() ? "/" : path);
    }

    /** The absolute server path of {@code path}, relative to the folder; the folder itself for {@code ""}. */
    public String resolve(String path) {
        if (path.isEmpty()) {
            return folder;
        }
        return folder.equals("/") ? "/" + path : folder + "/" + path;
    }
}
