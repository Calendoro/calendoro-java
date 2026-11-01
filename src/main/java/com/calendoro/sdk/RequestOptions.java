package com.calendoro.sdk;

import java.time.Duration;

/** Overrides for one call. */
public final class RequestOptions {
    String idempotencyKey;
    Duration timeout;

    /** Sets the Idempotency-Key of an idempotent operation. One is generated if omitted. */
    public RequestOptions idempotencyKey(String key) { this.idempotencyKey = key; return this; }

    /** Sets the timeout of each attempt of this call. */
    public RequestOptions timeout(Duration d) { this.timeout = d; return this; }
}
