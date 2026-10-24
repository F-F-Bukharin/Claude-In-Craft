package com.example.claudecodehelper.server;

import com.sun.net.httpserver.Headers;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/**
 * Who may call: the token check, and the guards that keep web pages from using a localhost server. A browser page
 * can't send JSON cross-origin without a preflight this server never answers; the Host check stops DNS rebinding,
 * and a foreign Origin is refused outright.
 */
final class Auth {
    private static final Set<String> LOCAL = Set.of("127.0.0.1", "localhost", "[::1]");

    private Auth() {}

    /** With no token any key (or none) passes; with one, {@code x-api-key} or {@code Authorization: Bearer} must match. */
    static boolean allowed(Headers h, String token) {
        if (token == null) return true;
        String key = h.getFirst("x-api-key");
        if (key != null && same(key.strip(), token)) return true;
        String auth = h.getFirst("Authorization");
        if (auth != null && auth.length() > 7 && auth.substring(0, 7).equalsIgnoreCase("bearer ")) {
            return same(auth.substring(7).strip(), token);
        }
        return false;
    }

    /** Null when the request is plainly local, else why not. */
    static String notLocal(Headers h, int port) {
        String host = h.getFirst("Host");
        if (host == null || !localHost(host.strip(), port)) return "this server only answers requests addressed to 127.0.0.1 or localhost";
        String origin = h.getFirst("Origin");
        if (origin != null && !origin.equals("null")) {
            try {
                String oh = URI.create(origin.strip()).getHost();
                if (oh == null || !(oh.equals("127.0.0.1") || oh.equals("localhost") || oh.equals("[::1]") || oh.equals("::1"))) {
                    return "requests from web pages on other sites are refused";
                }
            } catch (IllegalArgumentException e) {
                return "requests from web pages on other sites are refused";
            }
        }
        return null;
    }

    private static boolean localHost(String host, int port) {
        String lower = host.toLowerCase(Locale.ROOT);
        int colon = lower.lastIndexOf(':');
        String name = lower;
        if (colon > 0 && colon > lower.lastIndexOf(']')) {
            name = lower.substring(0, colon);
            if (!lower.substring(colon + 1).equals(Integer.toString(port))) return false;
        }
        return LOCAL.contains(name);
    }

    /** Constant time, so the token can't be guessed byte by byte from timings. */
    private static boolean same(String given, String token) {
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
