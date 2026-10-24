package com.example.claudecodehelper.server;

import java.security.SecureRandom;

/** Fresh ids in the API's style: msg_..., toolu_..., req_... */
final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Ids() {}

    static String next(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 24; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
