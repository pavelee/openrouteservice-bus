package org.heigit.ors.routing.graphhopper.extensions.weighting;

import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.weighting.ShortestWeighting;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.Parameters;
import org.heigit.ors.routing.graphhopper.extensions.flagencoders.FlagEncoderKeys;

import static com.graphhopper.routing.util.EncodingManager.getKey;

/**
 * ShortestWeighting with heading penalty and road class bias.
 *
 * ZASIĘG W PRODUKCJI (2026-07): aplikacja Traska ZAWSZE wysyła custom_model,
 * więc ORSWeightingFactory idzie ścieżką CustomModelParser/CustomWeighting i ta
 * klasa NIE bierze udziału w produkcyjnym routingu. Działa tylko dla surowych
 * zapytań bez custom_model (ręczny debug). Dodatkowo ROAD_CLASS_BIAS jest dla
 * profilu driving-bus no-opem: BusFlagEncoder.collect() daje wszystkim
 * krawędziom BEST (priority=1.0 → mnożnik 1.0). Zostawiona świadomie, żeby
 * debugowe zapytania bez custom_model nie robiły nawrotek na waypointach
 * (heading penalty) — usunięcie zmieniłoby tylko zachowanie debugowania.
 *
 * Standard ShortestWeighting treats all road types equally and ignores UNFAVORED_EDGE.
 * This version:
 * 1) Adds heading penalty for UNFAVORED_EDGE to prevent U-turns at waypoints
 * 2) Adds a small road class bias so the router prefers main roads when
 *    the distance difference is small (e.g. 300m main road vs 280m side street)
 *
 * The bias formula: weight = distance * (1 + BIAS * (1 - priority))
 * With BIAS=2.0:
 *   BEST(7)    priority=1.0  → multiplier=1.00 (no penalty)
 *   VERY_NICE(6) priority=0.86 → multiplier=1.29
 *   PREFER(5)  priority=0.71 → multiplier=1.57
 *   UNCHANGED(4) priority=0.57 → multiplier=1.86
 *   REACH_DEST(2) priority=0.29 → multiplier=2.43
 *
 * This means a 300m residential (PREFER) street costs as much as 471m of primary (BEST).
 * Oświatowa case (300m residential vs 600m primary detour): 471 < 600 → residential wins ✓
 * Wałowicka case (300m residential vs 400m primary): 471 > 400 → primary wins ✓
 *
 * HEADING_PENALTY is in meters (not seconds!) since ShortestWeighting works in distance domain.
 * 5000m penalty means a U-turn is never worth it unless the alternative adds 5km.
 */
public class ORSShortestWeighting extends ShortestWeighting {
    // 5km penalty for U-turn — in distance domain (meters), not time
    private static final double HEADING_PENALTY = 5000.0;
    private static final double ROAD_CLASS_BIAS = 2.0;
    private final DecimalEncodedValue priorityEncoder;

    public ORSShortestWeighting(FlagEncoder encoder) {
        super(encoder);
        this.priorityEncoder = findPriorityEncoder(encoder);
    }

    public ORSShortestWeighting(FlagEncoder encoder, TurnCostProvider turnCostProvider) {
        super(encoder, turnCostProvider);
        this.priorityEncoder = findPriorityEncoder(encoder);
    }

    private static DecimalEncodedValue findPriorityEncoder(FlagEncoder encoder) {
        try {
            return encoder.getDecimalEncodedValue(getKey(encoder, FlagEncoderKeys.PRIORITY_KEY));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        double weight = super.calcEdgeWeight(edgeState, reverse);
        if (Double.isInfinite(weight))
            return weight;

        // Road class bias: slightly penalize lower-priority roads
        if (priorityEncoder != null) {
            double priority = priorityEncoder.getDecimal(reverse, edgeState.getFlags());
            weight *= (1.0 + ROAD_CLASS_BIAS * (1.0 - priority));
        }

        // Heading penalty: prevent U-turns at waypoints
        boolean unfavoredEdge = edgeState.get(EdgeIteratorState.UNFAVORED_EDGE);
        if (unfavoredEdge)
            weight += HEADING_PENALTY;

        return weight;
    }

    @Override
    public String getName() {
        return "shortest";
    }
}
