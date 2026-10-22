package com.example.claudeplayer.core.act;

/** Runs actions in the game. Implemented on the game side; every call happens on the server thread. */
public interface ActionRunner {
    Handle start(Action action);

    /** A started action. Quick actions return {@link #done}; timed ones finish on a later poll. */
    interface Handle {
        /** Called once per tick until it returns a result; {@code null} means still running. */
        ActionResult poll();

        /** Stop early (timeout, cancel, interrupt). Must leave the body standing still. */
        default void cancel() {}

        /** Ticks before the queue gives up with {@code failed:timeout}; 0 means the queue default. */
        default int timeoutTicks() {
            return 0;
        }

        static Handle done(ActionResult result) {
            return () -> result;
        }
    }
}
