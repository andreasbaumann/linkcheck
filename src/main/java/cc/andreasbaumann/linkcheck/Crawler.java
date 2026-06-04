package cc.andreasbaumann.linkcheck;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Crawler {

    private static final String USER_AGENT = "LinkCheckBot/1.0";
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int REQUEST_TIMEOUT_SEC = 20;

    private final HttpClient httpClient;
    private final RobotsParser robotsParser;
    private final FileStore fileStore;
    private final boolean verbose;
    private final int maxDepth;
    private final boolean continueMode;
    private final boolean followLinks;
    private final boolean ignoreCrawlDelay;
    private final boolean ignoreRobots;
    private final long brokenDelayMs;
    private final int maxRetries;
    private final boolean checkLastmod;
    private final boolean checkHead;
    private final boolean keepAlive;
    private final int numThreads;

    // Thread-safe shared state
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final List<LinkResult> results = Collections.synchronizedList(new ArrayList<>());
    // Counts items in the queue plus items currently being processed.
    // Safe termination: when this reaches 0, all work is done.
    private final AtomicInteger workRemaining = new AtomicInteger(0);

    private String baseHost;
    private long crawlDelayMs = 1000;
    private LinkedBlockingQueue<UrlEntry> queue;

    public Crawler(FileStore fileStore, boolean verbose, int maxDepth, boolean continueMode,
                   boolean followLinks, boolean ignoreCrawlDelay, boolean ignoreRobots,
                   long brokenDelayMs, int maxRetries, boolean checkLastmod, boolean checkHead,
                   boolean keepAlive, int numThreads) {
        this.fileStore = fileStore;
        this.verbose = verbose;
        this.maxDepth = maxDepth;
        this.continueMode = continueMode;
        this.followLinks = followLinks;
        this.ignoreCrawlDelay = ignoreCrawlDelay;
        this.ignoreRobots = ignoreRobots;
        this.brokenDelayMs = brokenDelayMs;
        this.maxRetries = maxRetries;
        this.checkLastmod = checkLastmod;
        this.checkHead = checkHead;
        this.keepAlive = keepAlive;
        this.numThreads = numThreads;
        this.robotsParser = new RobotsParser();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public List<LinkResult> crawl(String startUrl) throws IOException, InterruptedException {
        URI startUri = URI.create(startUrl);
        baseHost = startUri.getHost();

        fetchAndProcessRobots(startUri);
        if (ignoreCrawlDelay) {
            crawlDelayMs = 0;
        } else if (robotsParser.getCrawlDelayMs() > 0) {
            crawlDelayMs = robotsParser.getCrawlDelayMs();
        }

        queue = new LinkedBlockingQueue<>();

        for (String sitemapUrl : robotsParser.getSitemapUrls()) {
            fetchAndStoreSitemap(sitemapUrl);
        }

        if (workRemaining.get() == 0) {
            System.out.println("No URLs to crawl: no sitemaps found or sitemaps were empty.");
            return results;
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            executor.submit(this::workerLoop);
        }
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

        return results;
    }

    private void workerLoop() {
        try {
            while (workRemaining.get() > 0) {
                UrlEntry entry = queue.poll(100, TimeUnit.MILLISECONDS);
                if (entry == null) continue;
                try {
                    boolean fetched = processUrl(entry);
                    if (fetched && crawlDelayMs > 0) Thread.sleep(crawlDelayMs);
                } finally {
                    workRemaining.decrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fetchAndProcessRobots(URI baseUri) throws InterruptedException {
        String robotsUrl = baseUri.getScheme() + "://" + baseUri.getHost()
            + (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : "") + "/robots.txt";
        System.out.println("Fetching robots.txt: " + robotsUrl);
        try {
            HttpResponse<String> resp = fetch(robotsUrl);
            if (resp.statusCode() == 200) {
                String body = resp.body();
                robotsParser.parse(body);
                if (fileStore != null) {
                    fileStore.store(robotsUrl, body);
                    System.out.println("  Stored: " + fileStore.getStorePath(robotsUrl));
                }
                if (verbose) System.out.println("  Parsed robots.txt, sitemaps: " + robotsParser.getSitemapUrls());
            } else {
                System.out.println("  robots.txt not found (" + resp.statusCode() + "), crawling unrestricted");
            }
        } catch (IOException e) {
            System.out.println("  Could not fetch robots.txt: " + e.getMessage());
        }
    }

    private void fetchAndStoreSitemap(String sitemapUrl) throws InterruptedException {
        System.out.println("Fetching sitemap: " + sitemapUrl);
        try {
            HttpResponse<String> resp = fetch(sitemapUrl);
            if (resp.statusCode() == 200) {
                String body = resp.body();
                if (fileStore != null) {
                    fileStore.store(sitemapUrl, body);
                    System.out.println("  Stored: " + fileStore.getStorePath(sitemapUrl));
                }
                SitemapParser.ParseResult parsed = SitemapParser.parse(body);
                if (parsed.isSitemapIndex()) {
                    for (SitemapParser.SitemapEntry child : parsed.getEntries()) {
                        fetchAndStoreSitemap(child.getUrl());
                    }
                } else {
                    int added = 0;
                    for (SitemapParser.SitemapEntry entry : parsed.getEntries()) {
                        if (enqueue(entry.getUrl(), sitemapUrl, 0, entry.getLastmod())) added++;
                    }
                    if (verbose) System.out.println("  Added " + added + " URLs from sitemap");
                }
            } else {
                System.err.println("  Sitemap returned " + resp.statusCode());
            }
        } catch (IOException e) {
            System.err.println("  Could not fetch sitemap: " + e.getMessage());
        }
    }

    /**
     * Atomically marks a URL as visited and adds it to the work queue.
     * Increments workRemaining BEFORE adding to the queue so the termination
     * counter is always consistent. Returns true if the URL was newly enqueued.
     */
    private boolean enqueue(String url, String referrer, int depth, Instant lastmod) {
        String normalized = normalizeUrl(url);
        if (visited.add(normalized)) {
            workRemaining.incrementAndGet();
            queue.add(new UrlEntry(normalized, referrer, depth, lastmod));
            return true;
        }
        return false;
    }

    /** Returns true if a real network request was made (caller should apply crawl delay). */
    private boolean processUrl(UrlEntry entry) throws InterruptedException {
        String url = entry.url;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            results.add(new LinkResult(url, entry.referrer, 0, LinkResult.Status.ERROR, "Invalid URL: " + e.getMessage()));
            return false;
        }

        boolean isSameHost = baseHost.equalsIgnoreCase(uri.getHost());

        // check robots.txt
        if (!ignoreRobots && isSameHost && !robotsParser.isAllowed(uri.getPath() != null ? uri.getPath() : "/")) {
            if (verbose) System.out.println("  SKIP (robots): " + url);
            results.add(new LinkResult(url, entry.referrer, 0, LinkResult.Status.SKIPPED, "disallowed by robots.txt"));
            return false;
        }

        // continue mode: check cache freshness and reuse if still valid
        if (continueMode && fileStore != null && fileStore.exists(url)) {
            boolean stale = false;

            if (checkLastmod && entry.lastmod != null) {
                try {
                    Instant fileMtime = fileStore.getLastModified(url);
                    if (entry.lastmod.isAfter(fileMtime)) {
                        stale = true;
                        if (verbose) System.out.println("  STALE (lastmod): " + url);
                    }
                } catch (IOException e) {
                    if (verbose) System.err.println("  Could not read file mtime: " + url);
                }
            }

            if (!stale && checkHead) {
                stale = isCacheStaleByHead(url);
            }

            if (!stale) {
                if (verbose) System.out.println("  CACHED: " + url);
                results.add(new LinkResult(url, entry.referrer, 200, LinkResult.Status.OK, "cached"));
                if (followLinks && isSameHost && entry.depth < maxDepth) {
                    try {
                        String cached = fileStore.readString(url);
                        if (cached.trim().startsWith("<") || cached.contains("<html")) {
                            extractAndEnqueueLinks(url, cached, entry.depth + 1);
                        }
                    } catch (IOException e) {
                        if (verbose) System.err.println("  Could not read cached file: " + url);
                    }
                }
                return false;
            }
            // stale — fall through to re-download below
        }

        // fetch with retries
        HttpResponse<String> resp = null;
        IOException fetchError = null;
        int attempt = 0;

        while (true) {
            fetchError = null;
            try {
                resp = isSameHost ? fetch(url) : head(url);
            } catch (IOException e) {
                fetchError = e;
            }

            boolean needsRetry = fetchError != null
                || (resp != null && resp.statusCode() >= 400);

            if (!needsRetry || attempt >= maxRetries) break;

            attempt++;
            String reason = fetchError != null ? fetchError.getMessage()
                                               : "HTTP " + resp.statusCode();
            System.out.println("  Retry " + attempt + "/" + maxRetries + " (" + reason + "): " + url);
            if (brokenDelayMs > 0) Thread.sleep(brokenDelayMs);
        }

        // record final outcome
        if (fetchError != null) {
            results.add(new LinkResult(url, entry.referrer, 0, LinkResult.Status.ERROR, fetchError.getMessage()));
            System.out.println("  [ERROR]: " + url + " - " + fetchError.getMessage()
                + (attempt > 0 ? " (after " + attempt + " retr" + (attempt == 1 ? "y" : "ies") + ")" : ""));
        } else {
            int status = resp.statusCode();
            LinkResult.Status linkStatus;
            if (status >= 200 && status < 300) linkStatus = LinkResult.Status.OK;
            else if (status >= 300 && status < 400) linkStatus = LinkResult.Status.REDIRECT;
            else linkStatus = LinkResult.Status.BROKEN;

            results.add(new LinkResult(url, entry.referrer, status, linkStatus, null));

            if (linkStatus == LinkResult.Status.BROKEN) {
                System.out.println("  [BROKEN " + status + "]: " + url
                    + (entry.referrer != null ? " <- " + entry.referrer : "")
                    + (attempt > 0 ? " (after " + attempt + " retr" + (attempt == 1 ? "y" : "ies") + ")" : ""));
            } else if (verbose) {
                System.out.println("  [" + status + "] " + url);
            }

            // store and optionally follow links for same-host HTML pages
            if (isSameHost && status == 200 && entry.depth < maxDepth) {
                String contentType = resp.headers().firstValue("content-type").orElse("");
                if (contentType.contains("text/html")) {
                    if (fileStore != null) {
                        try {
                            fileStore.store(url, resp.body());
                            stampLastModified(url, resp);
                        } catch (IOException e) {
                            if (verbose) System.err.println("  Could not store: " + url + " - " + e.getMessage());
                        }
                    }
                    if (followLinks) {
                        extractAndEnqueueLinks(url, resp.body(), entry.depth + 1);
                    }
                } else if (fileStore != null) {
                    storeBytes(url);
                }
            }
        }
        return true;
    }

    private void extractAndEnqueueLinks(String pageUrl, String html, int depth) {
        Document doc = Jsoup.parse(html, pageUrl);
        Elements links = doc.select("a[href], link[href], img[src], script[src], iframe[src]");
        for (Element el : links) {
            String raw = el.hasAttr("href") ? el.attr("abs:href") : el.attr("abs:src");
            if (raw == null || raw.isEmpty()) continue;
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) continue;
            enqueue(raw, pageUrl, depth, null);
        }
    }

    /**
     * Strips URL fragments and ensures that root URLs (no path) have a trailing slash,
     * so that "http://example.com" and "http://example.com/" are treated as the same URL.
     */
    static String normalizeUrl(String url) {
        int frag = url.indexOf('#');
        if (frag >= 0) url = url.substring(0, frag);

        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                url = url + "/";
            }
        } catch (IllegalArgumentException ignored) {}

        return url;
    }

    /** Returns true if the cached file is stale according to a HEAD request. */
    private boolean isCacheStaleByHead(String url) throws InterruptedException {
        try {
            Instant fileMtime = fileStore.getLastModified(url);
            String ifModifiedSince = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(fileMtime.atZone(ZoneOffset.UTC));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("If-Modified-Since", ifModifiedSince)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<Void> resp = client().send(req, HttpResponse.BodyHandlers.discarding());

            if (resp.statusCode() == 304) return false;

            Optional<String> lastModHeader = resp.headers().firstValue("last-modified");
            if (lastModHeader.isPresent()) {
                try {
                    Instant serverTime = DateTimeFormatter.RFC_1123_DATE_TIME
                        .parse(lastModHeader.get(), Instant::from);
                    if (!serverTime.isAfter(fileMtime)) return false;
                } catch (DateTimeParseException ignored) {}
            }
            if (verbose) System.out.println("  STALE (HEAD): " + url);
            return true;
        } catch (IOException e) {
            if (verbose) System.err.println("  HEAD check failed for " + url + ": " + e.getMessage());
            return false;
        }
    }

    /** Sets the stored file's mtime to the server's Last-Modified date, if present. */
    private void stampLastModified(String url, HttpResponse<String> resp) {
        resp.headers().firstValue("last-modified").ifPresent(lm -> {
            try {
                Instant serverTime = DateTimeFormatter.RFC_1123_DATE_TIME.parse(lm, Instant::from);
                fileStore.setLastModified(url, serverTime);
            } catch (Exception ignored) {}
        });
    }

    /**
     * Returns the shared client when keep-alive is on (connections are pooled/reused),
     * or a fresh client per call when keep-alive is off (each request gets its own connection).
     */
    private HttpClient client() {
        if (keepAlive) return httpClient;
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    private void storeBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                .GET()
                .build();
            HttpResponse<byte[]> resp = client().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                fileStore.store(url, resp.body());
            }
        } catch (Exception e) {
            if (verbose) System.err.println("  Could not store binary: " + url);
        }
    }

    private HttpResponse<String> fetch(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
            .GET()
            .build();
        return client().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> head(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build();
        try {
            return client().send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return fetch(url);
        }
    }

    private static class UrlEntry {
        final String url;
        final String referrer;
        final int depth;
        final Instant lastmod;

        UrlEntry(String url, String referrer, int depth, Instant lastmod) {
            this.url = url;
            this.referrer = referrer;
            this.depth = depth;
            this.lastmod = lastmod;
        }
    }
}
