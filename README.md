# Calendoro for Java

**Status: not yet published to Maven Central.** The install command below works after the first release. Until then, use this folder directly.

Every method is generated from the Calendoro OpenAPI document. Java 17+. Uses `java.net.http` and Jackson.

```xml
<dependency>
  <groupId>com.calendoro</groupId>
  <artifactId>calendoro-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
Calendoro client = Calendoro.builder().apiKey("cdo_...").build(); // or .oauthToken("cdoa_...")

var me = client.account.getApiIdentity();

for (var hook : client.webhooks.listWebhooksIter(null, null)) { // follows nextCursor
    System.out.println(hook.url);
}

var booking = client.bookings.createBooking(
    new Models.CreateBookingRequest().eventTypeId("...").startAt("2026-11-02T15:00:00Z"),
    new RequestOptions().idempotencyKey("order-1234")); // generated for you if omitted
```

Models are classes in `Models` with public fields and fluent setters; null fields are not sent, so an explicit null cannot be sent through a model. Enums are strings, so a new server value cannot break old clients.

**Retries**: `.maxRetries(n)` (default 2), exponential backoff with full jitter, honoring `Retry-After`. GET, PUT, DELETE and calls with an idempotency key retry on network errors, 408, 429 and 5xx. Other writes retry only on 429, since a failed one may have taken effect.

**Timeouts**: `.timeout(Duration)` per attempt (default 30s), or `new RequestOptions().timeout(...)` per call.

**Errors**: unchecked `CalendoroException.Api` has `status`, `code`, `details`, `requestId`. Subclasses: `Validation`, `Authentication`, `PermissionDenied`, `NotFound`, `Conflict`, `RateLimit`, `Server`. Network failures throw `Connection` or `Timeout`.

**Webhooks**: pass the raw request body.

```java
JsonNode event = Webhooks.constructEvent(rawBody, request.getHeader("Calendoro-Signature"), "whsec_...");
```

Methods documented as needing a user session refuse API keys and OAuth tokens; use `.sessionToken(...)`.
