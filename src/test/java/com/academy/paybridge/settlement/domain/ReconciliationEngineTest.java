package com.academy.paybridge.settlement.domain;

import com.academy.paybridge.settlement.domain.ReconciliationEngine.Break;
import com.academy.paybridge.settlement.domain.ReconciliationEngine.BreakKind;
import com.academy.paybridge.settlement.domain.ReconciliationEngine.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationEngineTest {

    private static Line line(String reference, long cents) {
        return new Line(reference, cents);
    }

    @Test
    void identicalStatementsHaveNoBreaks() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000), line("INV-2", 20_000)),
                List.of(line("INV-2", 20_000), line("INV-1", 10_000)));   // order does not matter

        assertThat(breaks).isEmpty();
    }

    @Test
    void aLineWeHaveAndTheyDoNotIsMissing() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000), line("INV-2", 20_000)),
                List.of(line("INV-1", 10_000)));

        assertThat(breaks).containsExactly(
                new Break(BreakKind.MISSING_FROM_STATEMENT, "INV-2", 20_000L, null));
    }

    @Test
    void aLineTheyHaveAndWeDoNotIsUnexpected() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000)),
                List.of(line("INV-1", 10_000), line("INV-9", 7_500)));

        assertThat(breaks).containsExactly(
                new Break(BreakKind.UNEXPECTED_IN_STATEMENT, "INV-9", null, 7_500L));
    }

    @Test
    void theSameReferenceWithADifferentAmountIsAMismatch() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000)),
                List.of(line("INV-1", 9_999)));   // one cent out is still a break

        assertThat(breaks).containsExactly(
                new Break(BreakKind.AMOUNT_MISMATCH, "INV-1", 10_000L, 9_999L));
    }

    @Test
    void aReferenceListedTwiceIsADuplicate() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000)),
                List.of(line("INV-1", 10_000), line("INV-1", 10_000)));

        assertThat(breaks).containsExactly(
                new Break(BreakKind.DUPLICATE_IN_STATEMENT, "INV-1", 10_000L, 20_000L));
    }

    @Test
    void severalProblemsAtOnceComeBackInAStableOrder() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000), line("INV-2", 20_000), line("INV-3", 5_000)),
                List.of(line("INV-1", 10_000), line("INV-2", 15_000), line("INV-4", 7_500)));

        assertThat(breaks).extracting(Break::kind).containsExactly(
                BreakKind.MISSING_FROM_STATEMENT,       // INV-3
                BreakKind.UNEXPECTED_IN_STATEMENT,      // INV-4
                BreakKind.AMOUNT_MISMATCH);             // INV-2
    }

    @Test
    void anEmptyStatementMakesEverythingMissing() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000), line("INV-2", 20_000)), List.of());

        assertThat(breaks).hasSize(2).allMatch(b -> b.kind() == BreakKind.MISSING_FROM_STATEMENT);
    }

    @Test
    void referencesAreCaseSensitive() {
        List<Break> breaks = ReconciliationEngine.compare(
                List.of(line("INV-1", 10_000)), List.of(line("inv-1", 10_000)));

        assertThat(breaks).extracting(Break::kind).containsExactly(
                BreakKind.MISSING_FROM_STATEMENT, BreakKind.UNEXPECTED_IN_STATEMENT);
    }
}