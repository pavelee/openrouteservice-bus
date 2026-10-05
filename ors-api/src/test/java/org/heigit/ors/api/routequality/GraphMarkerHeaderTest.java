package org.heigit.ors.api.routequality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GraphMarkerHeaderTest {
    private String header(String mode, String ids, String extra) {
        return "{\"schema\":\"route-quality-graph-selection-v1\",\"mode\":\"" + mode + "\",\"disabledInterventionIds\":" + ids + extra + "}";
    }

    @Test
    @DisplayName("Brak nagłówka zachowuje dotychczasowy domyślny wybór")
    void noHeaderIsAbsentSelection() { assertNull(GraphMarkerHeader.parse(null)); }

    @Test
    @DisplayName("Cały moduł można wyłączyć bez odczytu statusu i rejestru")
    void offDoesNotRequirePolicyLookup() {
        var selection = GraphMarkerHeader.parse(header("OFF", "[]", ""));
        assertEquals("OFF", selection.mode());
        assertNull(selection.policySha256());
        assertEquals(List.of(), selection.disabledInterventionIds());
    }

    @Test
    @DisplayName("Wyłączenie pojedynczej poprawki wymaga wersji polityki")
    void individualExclusionIsPinned() {
        assertThrows(IllegalArgumentException.class, () -> GraphMarkerHeader.parse(header("ON", "[12]", "")));
        var selection = GraphMarkerHeader.parse(header("ON", "[12]", ",\"policySha256\":\"" + "a".repeat(64) + "\""));
        assertEquals(List.of(12L), selection.disabledInterventionIds());
        assertEquals("a".repeat(64), selection.policySha256());
    }

    @Test
    @DisplayName("Klasyczny graf ignoruje ogólny przełącznik, a markerowy zachowuje wybór także bez custom model")
    void onlyMarkerGraphsRequireDynamicWeights() throws Exception {
        var em = new com.graphhopper.routing.util.EncodingManager.Builder().add(new org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory()
                .createFlagEncoder("bus", new com.graphhopper.util.PMap("turn_costs=true"))).build();
        try (var graph = new com.graphhopper.storage.GraphBuilder(em).withTurnCosts(true).build()) {
            var off = new org.heigit.ors.routing.graphhopper.extensions.routequality.GraphMarkerSelection("OFF", null, List.of());
            assertNull(GraphMarkerHeader.validate(graph, off, 2003));
            var params = new org.heigit.ors.routing.RouteSearchParameters();
            assertFalse(params.requiresFullyDynamicWeights());
            graph.getProperties().put(org.heigit.ors.routing.graphhopper.extensions.routequality.GraphMarkerPolicy.PROPERTY,
                    java.nio.file.Files.readString(java.nio.file.Path.of("../script/test-fixtures/k9-small-marker-policy.json")));
            params.setGraphMarkerSelection(GraphMarkerHeader.validate(graph, off, 2003));
            assertTrue(params.requiresFullyDynamicWeights());
            assertEquals("OFF", params.getGraphMarkerSelection().mode());
            assertEquals("ON", GraphMarkerHeader.validate(graph, null, 2003).mode());
            var pinned = new org.heigit.ors.routing.graphhopper.extensions.routequality.GraphMarkerSelection("ON", "a".repeat(64), List.of(12L));
            var error = assertThrows(org.heigit.ors.exceptions.ParameterValueException.class, () -> GraphMarkerHeader.validate(graph, pinned, 2003));
            assertEquals(400, error.getStatusCode());
        }
    }

    @Test
    @DisplayName("Powtórzone pole nie może nadpisać wcześniej podanego trybu")
    void duplicateFieldsFail() {
        assertThrows(IllegalArgumentException.class, () -> GraphMarkerHeader.parse(header("OFF", "[]", ",\"mode\":\"ON\"")));
    }

    @Test
    @DisplayName("Niepoprawny nagłówek jest błędem klienta z kodem 400")
    void invalidHttpHeaderHasClientError() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(GraphMarkerHeader.NAME, "invalid");
        var error = assertThrows(org.heigit.ors.exceptions.ParameterValueException.class, () -> GraphMarkerHeader.read(request, 2003));
        assertEquals(400, error.getStatusCode());
        assertEquals(2003, error.getInternalCode());
    }

    @Test
    @DisplayName("Błędny tryb, flaga zamiast identyfikatora i nieznane pola są odrzucane")
    void invalidContractFails() {
        for (var value : new String[]{header("SHADOW", "[]", ""), header("ON", "[true]", ""), header("ON", "[]", ",\"official\":false"), "x"})
            assertThrows(IllegalArgumentException.class, () -> GraphMarkerHeader.parse(value));
        assertThrows(IllegalArgumentException.class, () -> GraphMarkerHeader.parse(" ".repeat(8193)));
    }
}
