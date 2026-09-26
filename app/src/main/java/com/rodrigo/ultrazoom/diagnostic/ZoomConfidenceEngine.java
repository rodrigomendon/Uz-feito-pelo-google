package com.rodrigo.ultrazoom.diagnostic;

public final class ZoomConfidenceEngine {
    public enum State { UNKNOWN, PARTIAL, CONFIRMED, FAIL, REVOKED }

    /** FAIL/REVOKED absorbs. UNKNOWN is missing evidence, not failure. */
    public static State combine(State a, State b, State c) {
        if (a == State.FAIL || b == State.FAIL || c == State.FAIL ||
            a == State.REVOKED || b == State.REVOKED || c == State.REVOKED) {
            return State.REVOKED;
        }
        State[] v = {
            a == null ? State.UNKNOWN : a,
            b == null ? State.UNKNOWN : b,
            c == null ? State.UNKNOWN : c
        };
        boolean anyConfirmed = false, anyPartial = false, anyUnknown = false;
        for (State s : v) {
            if (s == State.CONFIRMED) anyConfirmed = true;
            else if (s == State.PARTIAL) anyPartial = true;
            else if (s == State.UNKNOWN) anyUnknown = true;
        }
        if (anyPartial || (anyUnknown && anyConfirmed)) return State.PARTIAL;
        if (anyConfirmed) return State.CONFIRMED;
        return State.UNKNOWN;
    }

    /**
     * State transition rule for an individual diagnostic layer/level/pair:
     * - FAIL / REVOKED is absorbing.
     * - Subsequent UNKNOWN never revokes CONFIRMED or PARTIAL.
     * - Subsequent PARTIAL never downgrades CONFIRMED.
     */
    public static State transition(State current, State incoming) {
        State cur = current == null ? State.UNKNOWN : current;
        if (cur == State.FAIL || cur == State.REVOKED) return cur;
        if (incoming == null || incoming == State.UNKNOWN) return cur;
        if (incoming == State.FAIL) return State.FAIL;
        if (incoming == State.REVOKED) return State.REVOKED;
        if (cur == State.CONFIRMED) return State.CONFIRMED;
        if (incoming == State.CONFIRMED) return State.CONFIRMED;
        if (incoming == State.PARTIAL) return State.PARTIAL;
        return cur;
    }

    public static State fromLabel(String label) {
        if (label == null) return State.UNKNOWN;
        try {
            return State.valueOf(label.trim().toUpperCase(java.util.Locale.US));
        } catch (Exception ignored) {
            return State.UNKNOWN;
        }
    }

    private ZoomConfidenceEngine() {}
}
