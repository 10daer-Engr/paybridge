package com.academy.paybridge.shared.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    void generatedKeysCarryTheRequestedPrefix() {
        assertThat(ApiKeys.generate("pbk_")).startsWith("pbk_");
        assertThat(ApiKeys.generate("pbp_")).startsWith("pbp_");
    }

    @Test
    void generatedKeysAreLongAndDifferEveryTime() {
        String first = ApiKeys.generate("pbk_");
        String second = ApiKeys.generate("pbk_");

        assertThat(first).isNotEqualTo(second);
        assertThat(first.length()).isGreaterThan(40);
    }

    @Test
    void hashingIsDeterministicAndHidesTheKey() {
        String key = "pbk_example";

        assertThat(ApiKeys.hash(key)).isEqualTo(ApiKeys.hash(key));
        assertThat(ApiKeys.hash(key)).hasSize(64).matches("[0-9a-f]+").doesNotContain("example");
        assertThat(ApiKeys.hash(key)).isNotEqualTo(ApiKeys.hash("pbk_other"));
    }
}