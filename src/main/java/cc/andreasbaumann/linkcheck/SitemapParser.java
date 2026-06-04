package cc.andreasbaumann.linkcheck;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class SitemapParser {

    public static ParseResult parse(String xml) {
        List<SitemapEntry> entries = new ArrayList<>();
        boolean isSitemapIndex = false;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();

            String rootName = doc.getDocumentElement().getLocalName();
            if (rootName == null) rootName = doc.getDocumentElement().getNodeName();

            if ("sitemapindex".equals(rootName)) {
                isSitemapIndex = true;
                NodeList locs = doc.getElementsByTagName("loc");
                for (int i = 0; i < locs.getLength(); i++) {
                    String loc = locs.item(i).getTextContent().trim();
                    if (!loc.isEmpty()) entries.add(new SitemapEntry(loc, null));
                }
            } else {
                NodeList urlNodes = doc.getElementsByTagName("url");
                for (int i = 0; i < urlNodes.getLength(); i++) {
                    String loc = null;
                    Instant lastmod = null;
                    NodeList children = urlNodes.item(i).getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        Node child = children.item(j);
                        if ("loc".equals(child.getNodeName())) {
                            loc = child.getTextContent().trim();
                        } else if ("lastmod".equals(child.getNodeName())) {
                            lastmod = parseLastmod(child.getTextContent().trim());
                        }
                    }
                    if (loc != null && !loc.isEmpty()) {
                        entries.add(new SitemapEntry(loc, lastmod));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to parse sitemap XML: " + e.getMessage());
        }

        return new ParseResult(entries, isSitemapIndex);
    }

    /** Parses W3C datetime (date-only, local datetime, or offset datetime) into an Instant. */
    static Instant parseLastmod(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Instant.parse(value); // ISO-8601 with Z
        } catch (DateTimeParseException ignored) {}
        try {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value, Instant::from);
        } catch (DateTimeParseException ignored) {}
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {}
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {}
        return null;
    }

    public static class SitemapEntry {
        private final String url;
        private final Instant lastmod;

        public SitemapEntry(String url, Instant lastmod) {
            this.url = url;
            this.lastmod = lastmod;
        }

        public String getUrl() { return url; }
        public Instant getLastmod() { return lastmod; }
    }

    public static class ParseResult {
        private final List<SitemapEntry> entries;
        private final boolean sitemapIndex;

        public ParseResult(List<SitemapEntry> entries, boolean sitemapIndex) {
            this.entries = entries;
            this.sitemapIndex = sitemapIndex;
        }

        public List<SitemapEntry> getEntries() { return entries; }
        public boolean isSitemapIndex() { return sitemapIndex; }
    }
}
