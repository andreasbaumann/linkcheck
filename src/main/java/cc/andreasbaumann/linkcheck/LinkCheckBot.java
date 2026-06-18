/*
 * linkcheck - a Java link checker
 * Copyright (C) 2026 Andreas Baumann <mail@andreasbaumann.cc>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package cc.andreasbaumann.linkcheck;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
    name = "linkcheck",
    mixinStandardHelpOptions = true,
    version = "LinkCheckBot 1.0",
    description = "Crawls a website, respects robots.txt, and reports broken links."
)
public class LinkCheckBot implements Callable<Integer> {

    @Parameters(index = "0", description = "Starting URL to crawl (e.g. https://example.com)")
    private String url;

    @Option(names = {"-s", "--store"}, description = "Store downloaded files to disk")
    private boolean store;

    @Option(names = {"-d", "--store-dir"}, description = "Directory to store downloaded files (default: ${DEFAULT-VALUE})",
            defaultValue = "downloaded")
    private String storeDir;

    @Option(names = {"--max-depth"}, description = "Maximum crawl depth from the start URL (default: ${DEFAULT-VALUE})",
            defaultValue = "10")
    private int maxDepth;

    @Option(names = {"-v", "--verbose"}, description = "Print all visited URLs, not just errors")
    private boolean verbose;

    @Option(names = {"-C", "--continue"}, description = "Skip URLs whose files are already stored on disk; implies --store")
    private boolean continueMode;

    @Option(names = {"-f", "--follow-links"}, description = "Follow links found in HTML pages (default: only process sitemap URLs)")
    private boolean followLinks;

    @Option(names = {"--ignore-crawl-delay"}, description = "Ignore Crawl-delay from robots.txt and crawl as fast as possible")
    private boolean ignoreCrawlDelay;

    @Option(names = {"--ignore-robots"}, description = "Ignore robots.txt exclusion rules (Disallow directives)")
    private boolean ignoreRobots;

    @Option(names = {"--broken-delay"}, description = "Seconds to wait after each broken/error link (default: ${DEFAULT-VALUE})",
            defaultValue = "10")
    private int brokenDelaySec;

    @Option(names = {"--max-retries"}, description = "Max number of retries for broken/error links (default: ${DEFAULT-VALUE})",
            defaultValue = "3")
    private int maxRetries;

    @Option(names = {"--check-lastmod"}, description = "Re-download cached files when the sitemap lastmod is newer than the stored file (requires --store)")
    private boolean checkLastmod;

    @Option(names = {"--check-head"}, description = "Re-download cached files when a HEAD request indicates they have changed (requires --store)")
    private boolean checkHead;

    @Option(names = {"--keep-alive"}, description = "Use persistent HTTP connections (Connection: keep-alive)")
    private boolean keepAlive;

    @Option(names = {"-t", "--threads"}, description = "Number of parallel crawl threads (default: ${DEFAULT-VALUE})",
            defaultValue = "1")
    private int numThreads;

    @Option(names = {"--summary"}, description = "Print a summary at the end (default: true)",
            defaultValue = "true", negatable = true)
    private boolean summary;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LinkCheckBot()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // normalise URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        // ensure no trailing slash confusion for root
        if (url.matches("https?://[^/]+")) {
            url = url + "/";
        }

        if (continueMode || checkLastmod || checkHead) store = true;
        if (checkLastmod || checkHead) continueMode = true;

        System.out.println("LinkCheckBot starting crawl: " + url);
        if (store) System.out.println("Storing files to: " + storeDir);
        if (continueMode) System.out.println("Continue mode: skipping already-downloaded files");
        System.out.println("Follow links : " + followLinks);
        if (ignoreCrawlDelay) System.out.println("Crawl delay  : ignored");
        if (ignoreRobots) System.out.println("robots.txt   : exclusions ignored");
        System.out.println("Broken delay : " + brokenDelaySec + "s");
        System.out.println("Max retries  : " + maxRetries);
        if (checkLastmod) System.out.println("Check lastmod: enabled");
        if (checkHead)    System.out.println("Check HEAD   : enabled");
        System.out.println("Keep-alive   : " + keepAlive);
        System.out.println("Threads      : " + numThreads);
        System.out.println("Max depth    : " + maxDepth);
        System.out.println();

        FileStore fs = null;
        if (store) {
            try {
                fs = new FileStore(storeDir);
            } catch (IOException e) {
                System.err.println("Cannot create store directory: " + e.getMessage());
                return 1;
            }
        }

        Crawler crawler = new Crawler(fs, verbose, maxDepth, continueMode, followLinks,
                                      ignoreCrawlDelay, ignoreRobots, brokenDelaySec * 1000L, maxRetries,
                                      checkLastmod, checkHead, keepAlive, numThreads);
        List<LinkResult> results;
        try {
            results = crawler.crawl(url);
        } catch (IOException | InterruptedException e) {
            System.err.println("Crawl failed: " + e.getMessage());
            return 1;
        }

        if (summary) {
            printSummary(results);
        }

        boolean hasBroken = results.stream().anyMatch(LinkResult::isBroken);
        return hasBroken ? 2 : 0;
    }

    private void printSummary(List<LinkResult> results) {
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Total checked : " + results.size());
        System.out.println("OK (2xx)      : " + count(results, LinkResult.Status.OK));
        System.out.println("Redirects     : " + count(results, LinkResult.Status.REDIRECT));
        System.out.println("Broken        : " + count(results, LinkResult.Status.BROKEN));
        System.out.println("Errors        : " + count(results, LinkResult.Status.ERROR));
        System.out.println("Skipped       : " + count(results, LinkResult.Status.SKIPPED));

        List<LinkResult> broken = results.stream()
            .filter(LinkResult::isBroken)
            .collect(Collectors.toList());

        if (!broken.isEmpty()) {
            System.out.println();
            System.out.println("=== Broken / Error Links ===");
            broken.forEach(r -> System.out.println("  " + r));
        }
    }

    private long count(List<LinkResult> results, LinkResult.Status status) {
        return results.stream().filter(r -> r.getStatus() == status).count();
    }
}
