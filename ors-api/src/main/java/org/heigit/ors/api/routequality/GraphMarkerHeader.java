package org.heigit.ors.api.routequality;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.heigit.ors.exceptions.ParameterValueException;
import org.heigit.ors.routing.graphhopper.extensions.routequality.GraphMarkerPolicy;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.PMap;
import org.heigit.ors.routing.graphhopper.extensions.routequality.GraphMarkerSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class GraphMarkerHeader {
    public static final String NAME = "X-Traska-Route-Quality";
    private GraphMarkerHeader() {}

    public static GraphMarkerSelection read(HttpServletRequest request, int errorCode) throws ParameterValueException {
        try {
            return parse(request.getHeader(NAME));
        } catch (IllegalArgumentException error) {
            throw new ParameterValueException(errorCode, NAME);
        }
    }

    public static GraphMarkerSelection validate(GraphHopperStorage graph, GraphMarkerSelection selection, int errorCode) throws ParameterValueException {
        try {
            var hints = new PMap();
            GraphMarkerPolicy.installSelection(graph, hints, selection);
            return hints.getObject(GraphMarkerSelection.HINT, null);
        } catch (IllegalArgumentException error) {
            throw new ParameterValueException(errorCode, NAME);
        }
    }

    public static GraphMarkerSelection parse(String header) {
        if (header == null) return null;
        try {
            if (header.length() > 8192) throw new IllegalArgumentException("Graph selection header exceeds its budget");
            var document = new ObjectMapper()
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(header);
            if (document == null || !document.isObject() || !document.path("schema").asText().equals("route-quality-graph-selection-v1"))
                throw new IllegalArgumentException("Invalid graph selection header");
            var fields = Set.of("schema", "mode", "policySha256", "disabledInterventionIds");
            document.fieldNames().forEachRemaining(key -> {
                if (!fields.contains(key)) throw new IllegalArgumentException("Unknown graph selection field");
            });
            if (!document.path("mode").isTextual() || !document.path("disabledInterventionIds").isArray()
                    || document.has("policySha256") && !document.get("policySha256").isTextual())
                throw new IllegalArgumentException("Invalid graph selection fields");
            List<Long> disabled = new ArrayList<>();
            for (var identity : document.get("disabledInterventionIds")) {
                if (!identity.isIntegralNumber() || !identity.canConvertToLong()) throw new IllegalArgumentException("Invalid disabled graph identity");
                disabled.add(identity.asLong());
            }
            return new GraphMarkerSelection(document.get("mode").asText(), document.has("policySha256") ? document.get("policySha256").asText() : null, disabled);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid graph selection header JSON", error);
        }
    }
}
