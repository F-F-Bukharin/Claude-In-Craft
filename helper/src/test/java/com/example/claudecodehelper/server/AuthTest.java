package com.example.claudecodehelper.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

/** The token, and the guards against web pages reaching a localhost server. */
class AuthTest {
    private static Headers h(String... kv) {
        Headers h = new Headers();
        for (int i = 0; i < kv.length; i += 2) h.add(kv[i], kv[i + 1]);
        return h;
    }

    @Test
    void withoutATokenAnyKeyOrNonePasses() {
        assertTrue(Auth.allowed(h(), null));
        assertTrue(Auth.allowed(h("x-api-key", "sk-ant-whatever"), null));
    }

    @Test
    void withATokenItMustMatch() {
        assertTrue(Auth.allowed(h("x-api-key", "s3cret"), "s3cret"));
        assertTrue(Auth.allowed(h("Authorization", "Bearer s3cret"), "s3cret"));
        assertTrue(Auth.allowed(h("authorization", "bearer s3cret"), "s3cret"));
        assertFalse(Auth.allowed(h(), "s3cret"));
        assertFalse(Auth.allowed(h("x-api-key", "s3cre"), "s3cret"));
        assertFalse(Auth.allowed(h("x-api-key", "s3cretX"), "s3cret"));
        assertFalse(Auth.allowed(h("Authorization", "s3cret"), "s3cret"), "Bearer is required");
    }

    @Test
    void onlyLocalHostNamesAndOrigins() {
        assertNull(Auth.notLocal(h("Host", "127.0.0.1:8787"), 8787));
        assertNull(Auth.notLocal(h("Host", "localhost:8787"), 8787));
        assertNull(Auth.notLocal(h("Host", "[::1]:8787"), 8787));
        assertNotNull(Auth.notLocal(h("Host", "evil.example:8787"), 8787), "DNS rebinding");
        assertNotNull(Auth.notLocal(h(), 8787), "no Host header");
        assertNull(Auth.notLocal(h("Host", "127.0.0.1:8787", "Origin", "http://localhost:3000"), 8787));
        assertNotNull(Auth.notLocal(h("Host", "127.0.0.1:8787", "Origin", "https://evil.example"), 8787),
                "a web page elsewhere can't spend the plan");
    }
}
