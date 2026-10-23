package com.academy.paybridge.compliance.domain;

import com.academy.paybridge.compliance.domain.NameMatcher.Match;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameMatcherTest {

    private static final String LISTED = "Ivan Sanctionov";

    @Test
    void caseAndPunctuationDoNotHideAName() {
        assertThat(NameMatcher.match("IVAN   sanctionov!", LISTED, 0.85)).isEqualTo(Match.EXACT);
    }

    @Test
    void wordOrderDoesNotHideAName() {
        assertThat(NameMatcher.match("Sanctionov, Ivan", LISTED, 0.85)).isEqualTo(Match.EXACT);
    }

    @Test
    void accentsDoNotHideAName() {
        assertThat(NameMatcher.match("Iv\u00e1n Sanctionov", LISTED, 0.85)).isEqualTo(Match.EXACT);
    }

    @Test
    void aSlightMisspellingIsAPossibleMatchForAnAnalyst() {
        assertThat(NameMatcher.match("Ivan Sanctonov", LISTED, 0.85)).isEqualTo(Match.POSSIBLE);
    }

    @Test
    void anUnrelatedNameIsNotAMatch() {
        assertThat(NameMatcher.match("John Smith", LISTED, 0.85)).isEqualTo(Match.NONE);
    }

    @Test
    void veryShortNamesAreNeverFuzzyMatched() {
        // 80% similar, above the 75% threshold, but too short to mean anything.
        assertThat(NameMatcher.similarity("bo li", "bo la")).isGreaterThan(0.75);
        assertThat(NameMatcher.match("Bo Li", "Bo La", 0.75)).isEqualTo(Match.NONE);
    }

    @Test
    void theThresholdIsRespected() {
        assertThat(NameMatcher.match("Ivan Sanctonov", LISTED, 0.99)).isEqualTo(Match.NONE);
    }

    @Test
    void blankNamesNeverMatch() {
        assertThat(NameMatcher.match("   ", LISTED, 0.85)).isEqualTo(Match.NONE);
        assertThat(NameMatcher.match(null, LISTED, 0.85)).isEqualTo(Match.NONE);
    }
}