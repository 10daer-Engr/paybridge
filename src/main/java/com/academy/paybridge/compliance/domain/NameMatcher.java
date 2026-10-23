package com.academy.paybridge.compliance.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/** Compares a person's name with a listed name, ignoring case, accents, punctuation and word order. */
public final class NameMatcher {

    public enum Match { NONE, POSSIBLE, EXACT }

    /** Below this length a one-letter difference is a huge percentage, so fuzzy matching is meaningless. */
    private static final int MIN_LENGTH_FOR_FUZZY = 6;

    private NameMatcher() {
    }

    /** Lowercase, Latin accents removed, punctuation turned into spaces, spaces collapsed. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /** "smith john" and "john smith" become the same string. */
    public static String sortedTokens(String normalized) {
        if (normalized.isEmpty()) {
            return "";
        }
        String[] tokens = normalized.split(" ");
        Arrays.sort(tokens);
        return String.join(" ", tokens);
    }

    /** 1.0 for identical strings, falling towards 0.0 as more edits are needed. */
    public static double similarity(String a, String b) {
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / longest;
    }

    public static Match match(String candidateName, String listedName, double threshold) {
        String candidate = sortedTokens(normalize(candidateName));
        String listed = sortedTokens(normalize(listedName));

        if (candidate.isEmpty() || listed.isEmpty()) {
            return Match.NONE;
        }
        if (candidate.equals(listed)) {
            return Match.EXACT;
        }
        if (Math.min(candidate.length(), listed.length()) < MIN_LENGTH_FOR_FUZZY) {
            return Match.NONE;
        }
        return similarity(candidate, listed) >= threshold ? Match.POSSIBLE : Match.NONE;
    }

    /** The number of single-character insertions, deletions or substitutions between two strings. */
    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}