package com.rodrigo.ultrazoom.diagnostic;

public final class AfStateMachine {
    public enum State { IDLE, CANCELLING, STARTING, SCANNING, FOCUSED, FAILED, TIMEOUT }

    private State state = State.IDLE;
    private int attempts = 0;

    public void cancel() {
        state = State.CANCELLING;
    }

    public void start() {
        if (state == State.CANCELLING || state == State.IDLE || state == State.FOCUSED ||
            state == State.FAILED || state == State.TIMEOUT || state == State.SCANNING) {
            state = State.STARTING;
            attempts++;
        }
    }

    public void result(Integer af) {
        if (af == null) return;
        // Camera2 CONTROL_AF_STATE constants:
        // 0 = INACTIVE, 1 = PASSIVE_SCAN, 2 = PASSIVE_FOCUSED,
        // 3 = ACTIVE_SCAN, 4 = FOCUSED_LOCKED, 5 = NOT_FOCUSED_LOCKED, 6 = PASSIVE_UNFOCUSED
        if (af == 2 || af == 4) {
            state = State.FOCUSED;
        } else if (af == 5 || (af == 6 && (state == State.STARTING || state == State.SCANNING))) {
            state = State.FAILED;
        } else if (af == 1 || af == 3) {
            state = State.SCANNING;
        } else if (af == 0 && state != State.STARTING && state != State.CANCELLING) {
            state = State.IDLE;
        }
    }

    public void timeout() {
        if (state == State.STARTING || state == State.SCANNING || state == State.CANCELLING) {
            state = State.TIMEOUT;
        }
    }

    public boolean isTerminal() {
        return state == State.FOCUSED || state == State.FAILED || state == State.TIMEOUT;
    }

    public int getAttempts() {
        return attempts;
    }

    public State get() {
        return state;
    }

    private AfStateMachine() {}

    public static AfStateMachine create() {
        return new AfStateMachine();
    }
}
