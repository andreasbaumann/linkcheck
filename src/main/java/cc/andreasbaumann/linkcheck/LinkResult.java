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
