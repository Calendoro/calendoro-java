package com.calendoro.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SdkTest {
    record Seen(String method, String path, String auth, String key) {}

    HttpServer server;
    final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    int failures;
    Calendoro client;

    void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("X-Request-Id", "req_stub");
        x.sendResponseHeaders(status, raw.length);
        x.getResponseBody().write(raw);
        x.close();
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", x -> {
            String path = x.getRequestURI().getRawPath();
            String query = x.getRequestURI().getRawQuery();
            String method = x.getRequestMethod();
            x.getRequestBody().readAllBytes();
            seen.add(new Seen(method, path, x.getRequestHeaders().getFirst("Authorization"), x.getRequestHeaders().getFirst("Idempotency-Key")));
            boolean fail;
            synchronized (this) {
                fail = failures > 0;
                if (fail) failures--;
            }
            if (fail) {
                x.getResponseHeaders().set("Retry-After", "0");
                reply(x, 503, "{\"error\":{\"code\":\"stub_failure\",\"message\":\"try again\"}}");
            } else if (path.equals("/v1/webhooks")) {
                reply(x, 200, query != null && query.contains("cursor=p2") ? "{\"data\":[{\"id\":\"whk_2\"}],\"nextCursor\":null}" : "{\"data\":[{\"id\":\"whk_1\"}],\"nextCursor\":\"p2\"}");
            } else if (path.equals("/v1/me")) {
                reply(x, 200, "{\"organizationId\":\"org_1\",\"futureField\":1}");
            } else if (path.equals("/v1/bookings") && method.equals("POST")) {
                reply(x, 201, "{\"id\":\"bkg_1\",\"status\":\"confirmed\"}");
            } else if ((path.equals("/v1/event-types") || path.equals("/v1/auth/login")) && method.equals("POST")) {
                reply(x, 500, "{\"error\":{\"code\":\"internal\",\"message\":\"boom\"}}");
            } else if (path.startsWith("/v1/slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                x.close();
            } else {
                reply(x, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"nope\",\"details\":[\"x\"]}}");
            }
        });
        server.start();
        client = Calendoro.builder().apiKey("cdo_key").baseUrl(url()).build();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void auth() {
        assertEquals("org_1", client.account.getApiIdentity().organizationId);
        assertEquals("Bearer cdo_key", seen.get(0).auth());
        Calendoro.builder().oauthToken("cdoa_t").baseUrl(url()).build().account.getApiIdentity();
        assertEquals("Bearer cdoa_t", seen.get(1).auth());
    }

    @Test
    void retriesThenGivesUp() {
        failures = 1;
        client.account.getApiIdentity();
        assertEquals(2, seen.size());
        seen.clear();
        failures = 3;
        assertThrows(CalendoroException.Server.class, () -> client.account.getApiIdentity());
        assertEquals(3, seen.size());
    }

    @Test
    void idempotencyKeyStableAcrossRetries() {
        failures = 1;
        assertEquals("bkg_1", client.bookings.createBooking(new Models.CreateBookingRequest()).id);
        assertEquals(2, seen.size());
        assertNotNull(seen.get(0).key());
        assertEquals(seen.get(0).key(), seen.get(1).key());
        client.bookings.createBooking(new Models.CreateBookingRequest(), new RequestOptions().idempotencyKey("mine"));
        assertEquals("mine", seen.get(2).key());
    }

    @Test
    void writeWithoutKeyNotRetriedOn500() {
        assertThrows(CalendoroException.Server.class, () -> client.authentication.login(new Models.LoginRequest()));
        assertEquals(1, seen.size());
        assertNull(seen.get(0).key());
    }

    @Test
    void iteratorFollowsCursor() {
        List<String> ids = new ArrayList<>();
        client.webhooks.listWebhooksIter(null, null).forEach(w -> ids.add(w.id));
        assertEquals(List.of("whk_1", "whk_2"), ids);
    }

    @Test
    void typedErrorCarriesRequestId() {
        var e = assertThrows(CalendoroException.NotFound.class, () -> client.eventTypes.getEventType("a/b"));
        assertEquals(404, e.status);
        assertEquals("not_found", e.code);
        assertEquals("req_stub", e.requestId);
        assertEquals(List.of("x"), e.details);
        assertEquals("/v1/event-types/a%2Fb", seen.get(0).path());
    }

    @Test
    void timeout() {
        Calendoro fast = Calendoro.builder().apiKey("k").baseUrl(url()).timeout(Duration.ofMillis(100)).maxRetries(0).build();
        assertThrows(CalendoroException.Timeout.class, () -> fast.request("GET", "/v1/slow", Map.of(), Map.of(), null, false, null, new com.fasterxml.jackson.core.type.TypeReference<Object>() {}));
    }

    @Test
    void webhookVectorsMatchPackagesWebhooks() throws IOException {
        JsonNode vectors = Runtime.JSON.readTree(Files.readString(Path.of("fixtures", "webhook-vectors.json")));
        for (JsonNode v : vectors) {
            String header = v.get("header").isNull() ? null : v.get("header").asText();
            boolean got = Webhooks.verifySignature(header, v.get("secret").asText(), v.get("body").asText(), v.get("now").asLong(), v.path("tolerance").asLong(300));
            assertEquals(v.get("valid").asBoolean(), got, v.get("name").asText());
        }
        assertThrows(Webhooks.SignatureException.class, () -> Webhooks.constructEvent("{}", null, "s"));
    }
}
