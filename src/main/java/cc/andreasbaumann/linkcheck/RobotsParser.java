package cc.andreasbaumann.linkcheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses robots.txt and checks whether URLs are crawlable.
 * Respects rules for User-agent: LinkCheckBot and User-agent: *
 */
public class RobotsParser {

    private static final String BOT_NAME = "linkcheckbot";

    private final List<String> disallowRules = new ArrayList<>();
    private final List<String> allowRules = new ArrayList<>();
    private final List<String> sitemapUrls = new ArrayList<>();
    private long crawlDelayMs = 0;

    public void parse(String robotsTxt) {
        disallowRules.clear();
        allowRules.clear();
        sitemapUrls.clear();
        crawlDelayMs = 0;

        List<String> currentAgents = new ArrayList<>();
        List<String[]> pendingRules = new ArrayList<>();
        boolean inRelevantBlock = false;

        for (String rawLine : robotsTxt.split("\n")) {
            String line = rawLine.trim();
            // strip inline comments
            int commentIdx = line.indexOf('#');
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx).trim();
            }
            if (line.isEmpty()) {
                // blank line ends a block — commit pending rules if block was relevant
                if (inRelevantBlock && !pendingRules.isEmpty()) {
                    for (String[] rule : pendingRules) {
                        if ("disallow".equals(rule[0])) disallowRules.add(rule[1]);
                        else if ("allow".equals(rule[0])) allowRules.add(rule[1]);
                        else if ("crawl-delay".equals(rule[0])) {
                            try { crawlDelayMs = (long)(Double.parseDouble(rule[1]) * 1000); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                currentAgents.clear();
                pendingRules.clear();
                inRelevantBlock = false;
                continue;
            }

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            if ("sitemap".equals(key)) {
                if (!value.isEmpty()) sitemapUrls.add(value);
                continue;
            }

            if ("user-agent".equals(key)) {
                currentAgents.add(value.toLowerCase());
                if (value.equals("*") || value.equalsIgnoreCase("LinkCheckBot")) {
                    inRelevantBlock = true;
                }
                continue;
            }

            if (inRelevantBlock) {
                // prioritize LinkCheckBot rules over wildcard
                boolean isBotSpecific = currentAgents.contains(BOT_NAME);
                boolean isWildcard = currentAgents.contains("*") && !isBotSpecific;
                // we collect all; for conflicts, specific agent wins (checked at query time)
                if ("disallow".equals(key) || "allow".equals(key) || "crawl-delay".equals(key)) {
                    pendingRules.add(new String[]{key, value,
                        isBotSpecific ? "specific" : "wildcard"});
                }
            }
        }

        // handle file with no trailing newline
        if (inRelevantBlock && !pendingRules.isEmpty()) {
            for (String[] rule : pendingRules) {
                if ("disallow".equals(rule[0])) disallowRules.add(rule[1]);
                else if ("allow".equals(rule[0])) allowRules.add(rule[1]);
                else if ("crawl-delay".equals(rule[0])) {
                    try { crawlDelayMs = (long)(Double.parseDouble(rule[1]) * 1000); } catch (NumberFormatException ignored) {}
                }
            }
        }
    }

    /**
     * Returns true if the given path (e.g. "/foo/bar") is allowed to be crawled.
     */
    public boolean isAllowed(String path) {
        // find longest matching allow and disallow rule
        String bestAllow = null;
        String bestDisallow = null;

        for (String rule : allowRules) {
            if (rule.isEmpty()) continue;
            if (pathMatches(path, rule)) {
                if (bestAllow == null || rule.length() > bestAllow.length()) {
                    bestAllow = rule;
                }
            }
        }
        for (String rule : disallowRules) {
            if (rule.isEmpty()) continue; // empty disallow = allow all
            if (pathMatches(path, rule)) {
                if (bestDisallow == null || rule.length() > bestDisallow.length()) {
                    bestDisallow = rule;
                }
            }
        }

        if (bestDisallow == null) return true;
        if (bestAllow == null) return false;
        // most specific (longest) wins; allow wins on tie
        return bestAllow.length() >= bestDisallow.length();
    }

    private boolean pathMatches(String path, String rule) {
        if (rule.endsWith("*")) {
            return path.startsWith(rule.substring(0, rule.length() - 1));
        }
        if (rule.endsWith("$")) {
            return path.equals(rule.substring(0, rule.length() - 1));
        }
        return path.startsWith(rule);
    }

    public List<String> getSitemapUrls() { return sitemapUrls; }
    public long getCrawlDelayMs() { return crawlDelayMs; }
}
