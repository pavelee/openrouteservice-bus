package com.graphhopper.routing;

import java.util.concurrent.CancellationException;

public final class WaypointContinuityBudget {
    public static final String HINT = "ors.continuity_budget";
    private int remaining;

    public WaypointContinuityBudget(int maximumVisitedNodes) {
        if (maximumVisitedNodes <= 0) throw new IllegalArgumentException("A positive node budget is required");
        remaining = maximumVisitedNodes;
    }

    public static void checkCancellation() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Logical route cancelled");
    }

    public int remaining() {
        checkCancellation();
        if (remaining == 0) throw new Exhausted();
        return remaining;
    }

    public void consume(int visits) {
        if (visits < 0) throw new IllegalArgumentException("Negative visited-node count");
        remaining = Math.max(0, remaining - visits);
        remaining();
    }

    public static final class Exhausted extends RuntimeException {
        public Exhausted() { super("Logical route exceeded its total visited-node budget"); }
    }
}
