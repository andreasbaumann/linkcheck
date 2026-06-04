package cc.andreasbaumann.linkcheck;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

public class FileStore {

    private final Path storeDir;

    public FileStore(String storeDir) throws IOException {
        this.storeDir = Paths.get(storeDir);
        Files.createDirectories(this.storeDir);
    }

    /**
     * Converts a URL to a safe filesystem filename.
     * Strips scheme (http/https://), converts path separators to underscores,
     * and sanitizes any remaining unsafe characters.
     *
     * Examples:
     *   https://example.com/         -> example.com_
     *   https://example.com/a/b.html -> example.com_a_b.html
     *   https://example.com/robots.txt -> example.com_robots.txt
     */
    public static String urlToFilename(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() != null ? uri.getHost() : "";
            if (uri.getPort() != -1) {
                host += "_" + uri.getPort();
            }
            String path = uri.getPath() != null ? uri.getPath() : "";
            // strip leading slash, replace remaining slashes with underscores
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            path = path.replace("/", "_");

            String query = uri.getQuery();
            if (query != null && !query.isEmpty()) {
                // sanitize query: replace problematic chars
                query = "_" + query.replaceAll("[^a-zA-Z0-9=&._-]", "_");
            } else {
                query = "";
            }

            String filename = host + (path.isEmpty() ? "" : "_" + path) + query;
            // replace any remaining unsafe chars
            filename = filename.replaceAll("[<>:\"\\\\|?*\\x00-\\x1F]", "_");
            if (filename.isEmpty()) {
                filename = "_index";
            }
            return filename;
        } catch (IllegalArgumentException e) {
            return url.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
    }

    public void store(String url, byte[] content) throws IOException {
        String filename = urlToFilename(url);
        Path target = storeDir.resolve(filename);
        Files.write(target, content);
    }

    public void store(String url, String content) throws IOException {
        store(url, content.getBytes(StandardCharsets.UTF_8));
    }

    public boolean exists(String url) {
        return Files.exists(storeDir.resolve(urlToFilename(url)));
    }

    public String readString(String url) throws IOException {
        return new String(Files.readAllBytes(storeDir.resolve(urlToFilename(url))), StandardCharsets.UTF_8);
    }

    public Instant getLastModified(String url) throws IOException {
        return Files.getLastModifiedTime(storeDir.resolve(urlToFilename(url))).toInstant();
    }

    public void setLastModified(String url, Instant time) throws IOException {
        Files.setLastModifiedTime(storeDir.resolve(urlToFilename(url)), FileTime.from(time));
    }

    public Path getStorePath(String url) {
        return storeDir.resolve(urlToFilename(url));
    }
}
