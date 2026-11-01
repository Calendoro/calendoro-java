package com.calendoro.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** HTTP runtime: auth, retries, timeouts, idempotency keys, paging and errors. */
public class Runtime {
    public static final String VERSION = "0.1.0";
    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504);
    // Unknown fields are ignored so a newer server cannot break an older client.
    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Client settings. */
    @SuppressWarnings("unchecked")
    public abstract static class Config<B extends Config<B>> {
        String token;
        String baseUrl = "https://api.calendoro.app";
        Duration timeout = Duration.ofSeconds(30);
        int maxRetries = 2;
        HttpClient http;

        /** An API key, cdo_... */
        public B apiKey(String v) { token = v; return (B) this; }
        /** An OAuth access token, cdoa_... */
        public B oauthToken(String v) { token = v; return (B) this; }
        /** A user session token, cds_..., which session-only operations need. */
        public B sessionToken(String v) { token = v; return (B) this; }
        public B baseUrl(String v) { baseUrl = v.replaceAll("/+$", ""); return (B) this; }
        /** Timeout of each attempt. The default is 30 seconds. */
        public B timeout(Duration v) { timeout = v; return (B) this; }
        /** Retries after the first attempt. The default is 2. */
        public B maxRetries(int v) { maxRetries = v; return (B) this; }
        public B httpClient(HttpClient v) { http = v; return (B) this; }
    }

    /** One page of a list. */
    public static final class Page<T> {
        public List<T> data;
        public String nextCursor;
    }

    private final Config<?> config;
    private final HttpClient http;

    protected Runtime(Config<?> config) {
        this.config = config;
        this.http = config.http != null ? config.http : HttpClient.newHttpClient();
    }

    <T> T request(String method, String path, Map<String, String> pathParams, Map<String, Object> query, Object body, boolean idempotent, RequestOptions options, TypeReference<T> type) {
        String raw = send(method, path, pathParams, query, body, idempotent, options);
        try {
            return raw.isEmpty() ? null : JSON.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new CalendoroException("could not decode the response", e);
        }
    }

    <T> Iterable<T> paginate(String path, Map<String, String> pathParams, Map<String, Object> query, RequestOptions options, TypeReference<Page<T>> type) {
        return () -> new Iterator<T>() {
            private final Map<String, Object> q = new LinkedHashMap<>(query);
            private Iterator<T> items = List.<T>of().iterator();
            private boolean more = true;

            @Override public boolean hasNext() {
                while (!items.hasNext() && more) {
                    Page<T> page = request("GET", path, pathParams, q, null, false, options, type);
                    items = page.data == null ? List.<T>of().iterator() : page.data.iterator();
                    more = page.nextCursor != null && !page.nextCursor.isEmpty();
                    q.put("cursor", page.nextCursor);
                }
                return items.hasNext();
            }

            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return items.next();
            }
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String queryString(Map<String, Object> query) {
        List<String> pairs = new ArrayList<>();
        query.forEach((k, v) -> {
            if (v instanceof Iterable<?> many) many.forEach(x -> pairs.add(enc(k) + "=" + enc(String.valueOf(x))));
            else if (v != null) pairs.add(enc(k) + "=" + enc(String.valueOf(v)));
        });
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }

    private String send(String method, String path, Map<String, String> pathParams, Map<String, Object> query, Object body, boolean idempotent, RequestOptions options) {
        String p = path;
        for (Map.Entry<String, String> e : pathParams.entrySet()) p = p.replace("{" + e.getKey() + "}", enc(e.getValue()));
        Duration timeout = options != null && options.timeout != null ? options.timeout : config.timeout;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl + p + queryString(query))).timeout(timeout)
                .header("Accept", "application/json").header("User-Agent", "calendoro-java/" + VERSION);
        if (config.token != null) b.header("Authorization", "Bearer " + config.token);
        if (idempotent) {
            // One key for every retry of this call.
            b.header("Idempotency-Key", options != null && options.idempotencyKey != null ? options.idempotencyKey : UUID.randomUUID().toString());
        }
        try {
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } catch (JsonProcessingException e) {
            throw new CalendoroException("could not encode the body", e);
        }
        HttpRequest req = b.build();
        // A POST or PATCH without a key may already have taken effect, so only a 429, which the server refused up front, is safe to repeat.
        boolean safe = idempotent || Set.of("GET", "PUT", "DELETE").contains(method);
        for (int attempt = 0; ; attempt++) {
            double wait;
            try {
                return once(req);
            } catch (CalendoroException.Api e) {
                if (attempt >= config.maxRetries || !RETRYABLE.contains(e.status) || (!safe && e.status != 429)) throw e;
                wait = e.retryAfter != null ? Math.min(e.retryAfter, 60) : backoff(attempt);
            } catch (CalendoroException.Connection e) {
                if (attempt >= config.maxRetries || !safe) throw e;
                wait = backoff(attempt);
            }
            try {
                Thread.sleep((long) (wait * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CalendoroException.Connection("interrupted", e);
            }
        }
    }

    /** Exponential backoff with full jitter. */
    private static double backoff(int attempt) {
        return ThreadLocalRandom.current().nextDouble() * Math.min(8, 0.5 * Math.pow(2, attempt));
    }

    private String once(HttpRequest req) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new CalendoroException.Timeout("timed out", e);
        } catch (IOException e) {
            throw new CalendoroException.Connection(String.valueOf(e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CalendoroException.Connection("interrupted", e);
        }
        if (res.statusCode() < 400) return res.body();
        JsonNode err = null;
        try {
            err = JSON.readTree(res.body()).path("error");
        } catch (JsonProcessingException e) {
            // A proxy's HTML error page still becomes an Api error below.
        }
        boolean has = err != null && err.isObject();
        String requestId = res.headers().firstValue("x-request-id").orElse(has && err.hasNonNull("requestId") ? err.get("requestId").asText() : null);
        Double retryAfter = null;
        try {
            String header = res.headers().firstValue("retry-after").orElse(null);
            if (header != null) retryAfter = Math.max(0, Double.parseDouble(header));
        } catch (NumberFormatException e) {
            // An HTTP date: fall back to our own backoff.
        }
        Object details = has && err.hasNonNull("details") ? JSON.convertValue(err.get("details"), Object.class) : null;
        throw CalendoroException.Api.of(res.statusCode(), has ? err.path("code").asText("http_error") : "http_error", has ? err.path("message").asText("") : "HTTP " + res.statusCode(), details, requestId, retryAfter);
    }
}
