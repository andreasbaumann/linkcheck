# linkcheck

A command-line website link checker and crawler written in Java.

Given a starting URL, `linkcheck` fetches `robots.txt`, discovers URLs via sitemaps, and checks every link for broken responses. It exits with code `2` if any broken links are found, making it suitable for CI pipelines.

> Generated with [Claude Code](https://claude.ai/code) (claude-sonnet-4-8)

## Features

- Discovers URLs via `robots.txt` sitemaps (including sitemap indexes)
- Respects `robots.txt` `Disallow` rules and `Crawl-delay`
- Optionally follows `<a>`, `<link>`, `<img>`, `<script>`, and `<iframe>` links in HTML pages
- Disk caching with resume support (`--store`, `--continue`)
- Cache freshness checks via sitemap `lastmod` or HTTP `HEAD` requests
- Retry logic for broken/erroring links with configurable delay
- Multi-threaded crawling
- Keep-alive (persistent) HTTP connections
- Depth-limited link following

## Requirements

- Java 11 or newer
- Maven 3.x (to build)

## Build

```bash
mvn package
```

This produces a self-contained fat JAR at:

```
target/linkcheck-1.0.0-jar-with-dependencies.jar
```

## Usage

```bash
java -jar target/linkcheck-1.0.0-jar-with-dependencies.jar [OPTIONS] <URL>
```

### Examples

Check all links found in sitemaps:

```bash
java -jar linkcheck-1.0.0-jar-with-dependencies.jar https://example.com
```

Follow HTML links as well as sitemaps, with verbose output:

```bash
java -jar linkcheck-1.0.0-jar-with-dependencies.jar -f -v https://example.com
```

Store pages to disk and resume a previous run:

```bash
java -jar linkcheck-1.0.0-jar-with-dependencies.jar -C --store-dir cache https://example.com
```

Use 4 threads, ignore crawl delay, re-check stale cached pages:

```bash
java -jar linkcheck-1.0.0-jar-with-dependencies.jar -t 4 --ignore-crawl-delay --check-head -C https://example.com
```

## Options

| Option | Default | Description |
|--------|---------|-------------|
| `-s`, `--store` | false | Store downloaded files to disk |
| `-d`, `--store-dir` | `downloaded` | Directory for stored files |
| `--max-depth` | 10 | Maximum crawl depth from start URL |
| `-v`, `--verbose` | false | Print all visited URLs, not just errors |
| `-C`, `--continue` | false | Skip already-stored URLs; implies `--store` |
| `-f`, `--follow-links` | false | Follow links found in HTML pages |
| `--ignore-crawl-delay` | false | Ignore `Crawl-delay` from `robots.txt` |
| `--ignore-robots` | false | Ignore `robots.txt` exclusion rules |
| `--broken-delay` | 10 | Seconds to wait after each broken/error link |
| `--max-retries` | 3 | Max retries for broken/error links |
| `--check-lastmod` | false | Re-download cached files when sitemap `lastmod` is newer |
| `--check-head` | false | Re-download cached files when `HEAD` indicates a change |
| `--keep-alive` | false | Use persistent HTTP connections |
| `-t`, `--threads` | 1 | Number of parallel crawl threads |
| `--summary` / `--no-summary` | true | Print a summary at the end |
| `-h`, `--help` | | Show help |
| `-V`, `--version` | | Show version |

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | All links OK |
| 1 | Crawl failed to start (bad URL, missing store dir, etc.) |
| 2 | One or more broken or erroring links found |

## License

This program is free software, released under the [GNU General Public License v3.0](LICENSE).

## Generated with

This code was generated with [Claude Code](https://claude.ai/code) (Sonnet 4.8).
