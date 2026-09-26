package com.rodrigo.ultrazoom;

import com.rodrigo.ultrazoom.diagnostic.ZoomConfidenceEngine;
import org.junit.Test;
import static org.junit.Assert.*;

public class ZoomConfidenceEngineTest {
    @Test
    public void unknownDoesNotFail() {
        assertEquals(
                ZoomConfidenceEngine.State.PARTIAL,
                ZoomConfidenceEngine.combine(
                        ZoomConfidenceEngine.State.CONFIRMED,
                        ZoomConfidenceEngine.State.CONFIRMED,
                        ZoomConfidenceEngine.State.UNKNOWN));
    }

    @Test
    public void failIsAbsorbing() {
        assertEquals(
                ZoomConfidenceEngine.State.REVOKED,
                ZoomConfidenceEngine.combine(
                        ZoomConfidenceEngine.State.CONFIRMED,
                        ZoomConfidenceEngine.State.FAIL,
                        ZoomConfidenceEngine.State.UNKNOWN));
        assertEquals(
                ZoomConfidenceEngine.State.REVOKED,
                ZoomConfidenceEngine.combine(
                        ZoomConfidenceEngine.State.CONFIRMED,
                        ZoomConfidenceEngine.State.CONFIRMED,
                        ZoomConfidenceEngine.State.FAIL));
    }

    @Test
    public void allUnknown() {
        assertEquals(
                ZoomConfidenceEngine.State.UNKNOWN,
                ZoomConfidenceEngine.combine(
                        ZoomConfidenceEngine.State.UNKNOWN,
                        ZoomConfidenceEngine.State.UNKNOWN,
                        ZoomConfidenceEngine.State.UNKNOWN));
    }

    @Test
    public void allConfirmed() {
        assertEquals(
                ZoomConfidenceEngine.State.CONFIRMED,
                ZoomConfidenceEngine.combine(
                        ZoomConfidenceEngine.State.CONFIRMED,
                        ZoomConfidenceEngine.State.CONFIRMED,
                        ZoomConfidenceEngine.State.CONFIRMED));
    }

    @Test
    public void transitionPreservesConfirmedAgainstUnknownAndAbsorbsFail() {
        ZoomConfidenceEngine.State s = ZoomConfidenceEngine.State.UNKNOWN;
        s = ZoomConfidenceEngine.transition(s, ZoomConfidenceEngine.State.CONFIRMED);
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, s);
        // Subsequent UNKNOWN or PARTIAL must never revoke CONFIRMED
        s = ZoomConfidenceEngine.transition(s, ZoomConfidenceEngine.State.UNKNOWN);
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, s);
        s = ZoomConfidenceEngine.transition(s, ZoomConfidenceEngine.State.PARTIAL);
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, s);
        // FAIL revokes and is absorbing
        s = ZoomConfidenceEngine.transition(s, ZoomConfidenceEngine.State.FAIL);
        assertEquals(ZoomConfidenceEngine.State.FAIL, s);
        s = ZoomConfidenceEngine.transition(s, ZoomConfidenceEngine.State.CONFIRMED);
        assertEquals(ZoomConfidenceEngine.State.FAIL, s);
    }
}
