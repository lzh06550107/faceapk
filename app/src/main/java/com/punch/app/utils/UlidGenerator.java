package com.punch.app.utils;

import java.security.SecureRandom;


public class UlidGenerator {
    private static final String CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RNG = new SecureRandom();

    
    public static String generate() {
        long time = System.currentTimeMillis();
        return encodeTime(time) + encodeRandom();
    }

    
    private static String encodeTime(long time) {
        char[] chars = new char[10];
        for (int i = 9; i >= 0; i--) {
            chars[i] = CHARS.charAt((int) (time & 31));
            time >>= 5;
        }
        return new String(chars);
    }

    
    private static String encodeRandom() {
        char[] chars = new char[16];
        for (int i = 0; i < 16; i++) {
            chars[i] = CHARS.charAt(RNG.nextInt(32));
        }
        return new String(chars);
    }
}
