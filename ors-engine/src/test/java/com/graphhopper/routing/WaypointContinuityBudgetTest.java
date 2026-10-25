package com.graphhopper.routing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointContinuityBudgetTest {
    @Test void alternativesCannotResetTheBudget() {
        var budget = new WaypointContinuityBudget(10);
        budget.consume(4);
        budget.consume(5);
        assertEquals(1, budget.remaining());
        assertThrows(WaypointContinuityBudget.Exhausted.class, () -> budget.consume(2));
        assertThrows(WaypointContinuityBudget.Exhausted.class, budget::remaining);
    }

    @Test void rejectsInvalidLimitsAndCounters() {
        assertThrows(IllegalArgumentException.class, () -> new WaypointContinuityBudget(0));
        var budget = new WaypointContinuityBudget(10);
        assertThrows(IllegalArgumentException.class, () -> budget.consume(-1));
        assertEquals(10, budget.remaining());
    }
}
