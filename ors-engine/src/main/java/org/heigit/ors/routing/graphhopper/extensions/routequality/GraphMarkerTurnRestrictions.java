package org.heigit.ors.routing.graphhopper.extensions.routequality;

import com.graphhopper.reader.OSMTurnRelation;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.routing.ev.TurnCost;
import com.graphhopper.routing.util.AbstractFlagEncoder;
import com.graphhopper.routing.util.AccessFilter;
import com.graphhopper.storage.GraphHopperStorage;
import org.heigit.ors.routing.graphhopper.extensions.flagencoders.FlagEncoderNames;

import java.util.Arrays;

public final class GraphMarkerTurnRestrictions {
    public static final String TAG = "bus:quality_only_exclude";
    private GraphMarkerTurnRestrictions() {}

    public static boolean apply(GraphHopperStorage graph, OSMReader reader, ReaderRelation relation) {
        if (!relation.hasTag(TAG) || graph.getProperties().get("route_quality.marker_policy").isEmpty()) return false;
        if (!relation.hasTag(TAG, "v1") || !relation.hasTag("type", "restriction") || relation.getMembers().size() != 3)
            throw new IllegalArgumentException("Invalid graph marker turn restriction");
        long from = -1, to = -1, via = -1;
        for (var member : relation.getMembers()) {
            if (member.getType() == 1 && member.getRole().equals("from")) from = member.getRef();
            else if (member.getType() == 1 && member.getRole().equals("to")) to = member.getRef();
            else if (member.getType() == 0 && member.getRole().equals("via")) via = member.getRef();
            else throw new IllegalArgumentException("Invalid graph marker turn member");
        }
        if (from <= 0 || to <= 0 || via <= 0) throw new IllegalArgumentException("Invalid graph marker continuation");
        var em = graph.getEncodingManager();
        var encoder = (AbstractFlagEncoder) em.getEncoder(FlagEncoderNames.BUS);
        int node = reader.getInternalNodeIdOfOsmNode(via);
        if (node < 0 || !encoder.supportsTurnCosts()) return true;
        var exceptions = Arrays.stream(relation.<String>getTag("except", "").split(";")).map(String::trim).toList();
        boolean supported = false;
        for (var tag : relation.getTags().entrySet()) {
            if (!tag.getKey().equals("restriction") && !tag.getKey().startsWith("restriction:")) continue;
            if (OSMTurnRelation.Type.getRestrictionType(String.valueOf(tag.getValue())) != OSMTurnRelation.Type.ONLY) continue;
            supported = true;
            var restriction = new OSMTurnRelation(from, via, to, OSMTurnRelation.Type.ONLY);
            restriction.setVehicleTypeRestricted(tag.getKey().equals("restriction") ? "" : tag.getKey().substring("restriction:".length()));
            restriction.setVehicleTypesExcept(exceptions);
            if (!restriction.isVehicleTypeConcernedByTurnRestriction(encoder.getRestrictions())) continue;
            var incoming = graph.createEdgeExplorer(AccessFilter.inEdges(encoder.getAccessEnc())).setBaseNode(node);
            int selected = -1;
            while (incoming.next()) if (reader.getOsmIdOfInternalEdge(incoming.getEdge()) == from) {
                selected = incoming.getEdge();
                break;
            }
            if (selected < 0) continue;
            var outgoing = graph.createEdgeExplorer(AccessFilter.outEdges(encoder.getAccessEnc())).setBaseNode(node);
            while (outgoing.next()) if (outgoing.getEdge() != selected && reader.getOsmIdOfInternalEdge(outgoing.getEdge()) == to)
                graph.getTurnCostStorage().set(em.getDecimalEncodedValue(TurnCost.key(FlagEncoderNames.BUS)), selected, node, outgoing.getEdge(), Double.POSITIVE_INFINITY);
        }
        if (!supported) throw new IllegalArgumentException("Graph marker exclusion requires an ONLY restriction");
        return true;
    }
}
