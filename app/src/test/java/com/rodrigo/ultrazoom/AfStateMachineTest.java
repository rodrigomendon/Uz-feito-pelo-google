package com.rodrigo.ultrazoom;

import com.rodrigo.ultrazoom.diagnostic.AfStateMachine;
import org.junit.Test;
import static org.junit.Assert.*;

public class AfStateMachineTest {
    @Test
    public void cancelStartFocused() {
        AfStateMachine a = AfStateMachine.create();
        a.cancel();
        a.start();
        a.result(1);
        a.result(4);
        assertEquals(AfStateMachine.State.FOCUSED, a.get());
    }

    @Test
    public void timeoutIsExplicit() {
        AfStateMachine a = AfStateMachine.create();
        a.cancel();
        a.start();
        a.result(1);
        a.timeout();
        assertEquals(AfStateMachine.State.TIMEOUT, a.get());
    }

    @Test
    public void notFocusedLockedTransitionsToFailedAndRecovers() {
        AfStateMachine a = AfStateMachine.create();
        a.cancel();
        a.start();
        a.result(3); // ACTIVE_SCAN
        a.result(5); // NOT_FOCUSED_LOCKED
        assertEquals(AfStateMachine.State.FAILED, a.get());
        assertTrue(a.isTerminal());

        // Subsequent focus attempt must remain recoverable
        a.cancel();
        a.start();
        a.result(3);
        a.result(4); // FOCUSED_LOCKED
        assertEquals(AfStateMachine.State.FOCUSED, a.get());
        assertEquals(2, a.getAttempts());
    }

    @Test
    public void multipleTimeoutsRemainRecoverable() {
        AfStateMachine a = AfStateMachine.create();
        for (int i = 0; i < 4; i++) {
            a.cancel();
            a.start();
            a.result(3);
            a.timeout();
            assertEquals(AfStateMachine.State.TIMEOUT, a.get());
        }
        a.cancel();
        a.start();
        a.result(4);
        assertEquals(AfStateMachine.State.FOCUSED, a.get());
        assertEquals(5, a.getAttempts());
    }
}
