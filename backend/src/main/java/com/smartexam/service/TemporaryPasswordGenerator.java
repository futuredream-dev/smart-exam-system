package com.smartexam.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class TemporaryPasswordGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final char[] ALL = (UPPERCASE + LOWERCASE + DIGITS).toCharArray();

    public String generate() {
        List<Character> chars = new ArrayList<>(12);
        chars.add(UPPERCASE.charAt(RANDOM.nextInt(UPPERCASE.length())));
        chars.add(LOWERCASE.charAt(RANDOM.nextInt(LOWERCASE.length())));
        chars.add(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        while (chars.size() < 12) chars.add(ALL[RANDOM.nextInt(ALL.length)]);
        Collections.shuffle(chars, RANDOM);
        StringBuilder value = new StringBuilder(chars.size());
        chars.forEach(value::append);
        return value.toString();
    }
}
