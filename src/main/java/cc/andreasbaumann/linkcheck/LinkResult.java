package cc.andreasbaumann.linkcheck;

public class LinkResult {
    public enum Status { OK, REDIRECT, BROKEN, SKIPPED, ERROR }

    private final String url;
    private final String referrer;
    private final int httpStatus;
    private final Status status;
    private final String message;

    public LinkResult(String url, String referrer, int httpStatus, Status status, String message) {
        this.url = url;
        this.referrer = referrer;
        this.httpStatus = httpStatus;
        this.status = status;
        this.message = message;
    }

    public String getUrl() { return url; }
    public String getReferrer() { return referrer; }
    public int getHttpStatus() { return httpStatus; }
    public Status getStatus() { return status; }
    public String getMessage() { return message; }

    public boolean isBroken() { return status == Status.BROKEN || status == Status.ERROR; }

    @Override
    public String toString() {
        String ref = referrer != null ? " (found on: " + referrer + ")" : "";
        if (isBroken() && httpStatus > 0) {
            return String.format("[%s %d] %s%s", status, httpStatus, url, ref);
        }
        if (httpStatus > 0) {
            return String.format("[%d] %s%s", httpStatus, url, ref);
        }
        return String.format("[%s] %s%s%s", status, url, message != null ? " - " + message : "", ref);
    }
}
