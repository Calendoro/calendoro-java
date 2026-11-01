package com.calendoro.sdk;

/** Base class of every error this package throws. */
public class CalendoroException extends RuntimeException {
    public CalendoroException(String message, Throwable cause) { super(message, cause); }

    /** No response arrived. */
    public static class Connection extends CalendoroException {
        public Connection(String message, Throwable cause) { super(message, cause); }
    }

    /** The attempt took longer than the timeout. */
    public static final class Timeout extends Connection {
        public Timeout(String message, Throwable cause) { super(message, cause); }
    }

    /** The API answered with an error. Quote {@link #requestId} to support. */
    public static class Api extends CalendoroException {
        public final int status;
        public final String code;
        public final Object details;
        public final String requestId;
        public final Double retryAfter;

        public Api(int status, String code, String message, Object details, String requestId, Double retryAfter) {
            super(status + " " + code + ": " + message + (requestId == null ? "" : " (request " + requestId + ")"), null);
            this.status = status;
            this.code = code;
            this.details = details;
            this.requestId = requestId;
            this.retryAfter = retryAfter;
        }

        static Api of(int status, String code, String message, Object details, String requestId, Double retryAfter) {
            if (status >= 500) return new Server(status, code, message, details, requestId, retryAfter);
            return switch (status) {
                case 400 -> new Validation(status, code, message, details, requestId, retryAfter);
                case 401 -> new Authentication(status, code, message, details, requestId, retryAfter);
                case 403 -> new PermissionDenied(status, code, message, details, requestId, retryAfter);
                case 404 -> new NotFound(status, code, message, details, requestId, retryAfter);
                case 409 -> new Conflict(status, code, message, details, requestId, retryAfter);
                case 429 -> new RateLimit(status, code, message, details, requestId, retryAfter);
                default -> new Api(status, code, message, details, requestId, retryAfter);
            };
        }
    }

    public static final class Validation extends Api { Validation(int s, String c, String m, Object d, String r, Double a) { super(s, c, m, d, r, a); } }
    public static final class Authentication extends Api { Authentication(int s, String c, String m, Object d, String r, Double a) { super(s, c, m, d, r, a); } }
    public static final class PermissionDenied extends Api { PermissionDenied(int s, String c, String m, Object d, String r, Double a) { super(s, c, m, d, r, a); } }
    public static final class NotFound extends Api { NotFound(int s, String c, String m, Object d, String r, Double a) { super(s, c, m, d, r, a); } }
    public static final class Conflict extends Api { Conflict(int s, String c, String m, Object d, String r, Double a) { super(s, c, m, d, r, a); } }
    public static final class RateLimit extends Api { RateLimit(int s, String c, String m, Object d, String r, Double a) { super(s, c, m, d, r, a); } }
    public static final class Server extends Api { Server(int s, String c, String m, Object d, String r, Double a) { super(s, c, m, d, r, a); } }
}
