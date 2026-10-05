package org.heigit.ors.routing.graphhopper.extensions.routequality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class GraphMarkerSelectionTest {
    private final Path file = Path.of("../script/test-fixtures/k9-small-marker-policy.json");

    @Test
    @DisplayName("Wyłączenie całej jakości wybiera te same stany co jawna lista wszystkich jej identyfikatorów")
    void offMatchesEveryQualityExclusion() throws Exception {
        var json = Files.readString(file);
        var document = new ObjectMapper().readTree(json);
        var policy = GraphMarkerPolicy.parse(json);
        List<Long> ids = new ArrayList<>();
        document.get("qualityInterventionIds").forEach(id -> ids.add(id.asLong()));
        assertEquals(policy.selectedTokens(new GraphMarkerSelection("OFF", null, List.of())),
                policy.selectedTokens(new GraphMarkerSelection("ON", document.get("policySha256").asText(), ids)));
        assertNotEquals(policy.selectedTokens(new GraphMarkerSelection("ON", null, List.of())), policy.selectedTokens(new GraphMarkerSelection("OFF", null, List.of())));
    }

    @Test
    @DisplayName("Wyłączenie jednej poprawki zachowuje jedną kombinację w każdej lokalnej grupie")
    void everyGroupHasOneSelectedState() throws Exception {
        var json = Files.readString(file);
        var document = new ObjectMapper().readTree(json);
        var policy = GraphMarkerPolicy.parse(json);
        var selected = policy.selectedTokens(new GraphMarkerSelection("ON", document.get("policySha256").asText(), List.of(12L)));
        var groups = new HashSet<String>();
        for (var variant : document.get("variants")) if (selected.contains(variant.get("token").asInt()))
            assertTrue(groups.add(variant.get("interventionIds").toString()));
        var allGroups = new HashSet<String>();
        document.get("variants").forEach(v -> allGroups.add(v.get("interventionIds").toString()));
        assertEquals(allGroups, groups);
    }

    @Test
    @DisplayName("Oficjalna i nieznana poprawka oraz obcy skrót polityki są odrzucane")
    void onlyKnownQualityCanBeDisabled() throws Exception {
        var json = Files.readString(file);
        var document = new ObjectMapper().readTree(json);
        var policy = GraphMarkerPolicy.parse(json);
        for (long id : new long[]{99, 1000})
            assertThrows(IllegalArgumentException.class, () -> policy.selectedTokens(new GraphMarkerSelection("ON", document.get("policySha256").asText(), List.of(id))));
        assertThrows(IllegalArgumentException.class, () -> policy.selectedTokens(new GraphMarkerSelection("OFF", "a".repeat(64), List.of())));
    }

    @Test
    @DisplayName("Pojedynczy wpis wymagający przebudowy nie może udawać wyłączonego przez zapytanie")
    void rebuildOnlyCannotBeIndividuallyDisabled() throws Exception {
        var mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.get("qualityInterventionIds")).add(18);
        document.putArray("rebuildOnlyInterventionIds").add(18);
        java.util.Map<String, Object> body = mapper.convertValue(document, java.util.Map.class);
        body.remove("policySha256");
        document.put("policySha256", GraphMarkerPolicy.digest(mapper.writeValueAsBytes(body)));
        var policy = GraphMarkerPolicy.parse(document.toString());
        var selection = new GraphMarkerSelection("ON", document.get("policySha256").asText(), List.of(18L));
        assertThrows(IllegalArgumentException.class, () -> policy.selectedTokens(selection));
        assertEquals(GraphMarkerPolicy.parse(Files.readString(file)).selectedTokens(new GraphMarkerSelection("OFF", null, List.of())),
                policy.selectedTokens(new GraphMarkerSelection("OFF", null, List.of())));
    }
}
