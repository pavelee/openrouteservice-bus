package org.heigit.ors.routing.graphhopper.extensions.routequality;

import java.util.List;

public record GraphMarkerSelection(String mode, String policySha256, List<Long> disabledInterventionIds) {
    public static final String HINT = "ors.route_quality.graph_selection";

    public GraphMarkerSelection {
        if (!"ON".equals(mode) && !"OFF".equals(mode)) throw new IllegalArgumentException("Invalid graph marker mode");
        if (policySha256 != null && !policySha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid graph marker policy fingerprint");
        if (disabledInterventionIds == null || !disabledInterventionIds.isEmpty() && policySha256 == null)
            throw new IllegalArgumentException("Individual graph selection requires its policy fingerprint");
        long previous = 0;
        for (var identity : disabledInterventionIds) {
            if (identity == null || identity <= previous || identity > 9007199254740991L)
                throw new IllegalArgumentException("Disabled marker identities must be positive, sorted and unique");
            previous = identity;
        }
        disabledInterventionIds = List.copyOf(disabledInterventionIds);
    }
}
