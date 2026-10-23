package com.academy.paybridge.settlement.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compares our lines with the partner's statement. References are case-sensitive and amounts exact. */
public final class ReconciliationEngine {

    public enum BreakKind {
        MISSING_FROM_STATEMENT,
        UNEXPECTED_IN_STATEMENT,
        AMOUNT_MISMATCH,
        DUPLICATE_IN_STATEMENT
    }

    public record Line(String reference, long amountMinor) {
    }

    public record Break(BreakKind kind, String reference, Long ourAmountMinor, Long theirAmountMinor) {
    }

    private ReconciliationEngine() {
    }

    public static List<Break> compare(List<Line> ours, List<Line> theirs) {
        Map<String, Long> ourByReference = new LinkedHashMap<>();
        for (Line line : ours) {
            ourByReference.merge(line.reference(), line.amountMinor(), Long::sum);
        }

        Map<String, List<Long>> theirByReference = new LinkedHashMap<>();
        for (Line line : theirs) {
            theirByReference.computeIfAbsent(line.reference(), k -> new ArrayList<>()).add(line.amountMinor());
        }

        List<Break> breaks = new ArrayList<>();

        for (Map.Entry<String, Long> entry : ourByReference.entrySet()) {
            String reference = entry.getKey();
            List<Long> theirAmounts = theirByReference.get(reference);

            if (theirAmounts == null) {
                breaks.add(new Break(BreakKind.MISSING_FROM_STATEMENT, reference, entry.getValue(), null));
                continue;
            }
            if (theirAmounts.size() > 1) {
                breaks.add(new Break(BreakKind.DUPLICATE_IN_STATEMENT, reference, entry.getValue(), total(theirAmounts)));
            } else if (!theirAmounts.get(0).equals(entry.getValue())) {
                breaks.add(new Break(BreakKind.AMOUNT_MISMATCH, reference, entry.getValue(), theirAmounts.get(0)));
            }
        }

        for (Map.Entry<String, List<Long>> entry : theirByReference.entrySet()) {
            if (!ourByReference.containsKey(entry.getKey())) {
                breaks.add(new Break(BreakKind.UNEXPECTED_IN_STATEMENT, entry.getKey(), null, total(entry.getValue())));
            }
        }

        breaks.sort(Comparator.comparing(Break::kind).thenComparing(Break::reference));
        return List.copyOf(breaks);
    }

    private static long total(List<Long> amounts) {
        long sum = 0;
        for (long amount : amounts) {
            sum = Math.addExact(sum, amount);
        }
        return sum;
    }
}