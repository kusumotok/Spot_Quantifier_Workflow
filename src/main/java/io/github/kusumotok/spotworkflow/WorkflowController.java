package io.github.kusumotok.spotworkflow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WorkflowController {

    public enum State {
        IDLE,
        SEGMENTING,
        SAVING_ROI,
        LOADING_ROI,
        MEASURING,
        READY
    }

    private State state = State.IDLE;
    private final WorkflowSession session = new WorkflowSession();
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();

    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    public State getState() { return state; }
    public WorkflowSession getSession() { return session; }

    public boolean isBusy() {
        return state != State.IDLE && state != State.READY;
    }

    public void setState(State newState) {
        this.state = newState;
        for (Runnable l : stateListeners) l.run();
    }
}
