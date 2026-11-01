package com.calendoro.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Webhook signature verification, matching packages/webhooks. */
public final class Webhooks {
    private Webhooks() {}

    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    /** Thrown by {@link #constructEvent}. */
    public static final class SignatureException extends CalendoroException {
        public SignatureException() { super("invalid webhook signature", null); }
    }

    /** True when the Calendoro-Signature header signs the raw body with secret recently enough. False, never an exception, on bad input. */
    public static boolean verifySignature(String header, String secret, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) return false;
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",", -1)) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            String key = part.substring(0, i).strip();
            String value = part.substring(i + 1).strip();
            if (key.equals("t") && value.matches("[0-9]+")) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    // Too large to be a time; ignored like any other unusable part.
                }
            } else if (key.equals("v1")) {
                signatures.add(value);
            }
        }
        if (timestamp == null || Math.abs(nowSeconds - timestamp) > toleranceSeconds) return false;
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
        for (String s : signatures) {
            try {
                if (MessageDigest.isEqual(HexFormat.of().parseHex(s), expected)) return true;
            } catch (IllegalArgumentException e) {
                // Not hex: not a match.
            }
        }
        return false;
    }

    /** Verifies a delivery with the default tolerance and returns the parsed event. Pass the raw request body. */
    public static JsonNode constructEvent(String body, String header, String secret) {
        if (!verifySignature(header, secret, body, System.currentTimeMillis() / 1000, DEFAULT_TOLERANCE_SECONDS)) throw new SignatureException();
        try {
            return Runtime.JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new CalendoroException("could not decode the event", e);
        }
    }
}
