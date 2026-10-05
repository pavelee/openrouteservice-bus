package org.heigit.ors.routing.graphhopper.extensions.routequality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.PMap;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.routing.graphhopper.extensions.GraphProcessContext;
import org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;
import org.heigit.ors.routing.graphhopper.extensions.ORSOSMReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class GraphMarkerPolicyTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path fixtures = Path.of("../script/test-fixtures");

    private GraphHopperStorage graph() {
        var em = new EncodingManager.Builder().add(new ORSDefaultFlagEncoderFactory()
                .createFlagEncoder("bus", new PMap("turn_costs=true"))).build();
        return new GraphBuilder(em).withTurnCosts(true).build();
    }

    @Test
    @DisplayName("Zamrożona polityka przyjmuje znane tokeny i odrzuca nieznane")
    void validPolicyRejectsUnknownTokens() throws Exception {
        var policy = GraphMarkerPolicy.parse(Files.readString(fixtures.resolve("k9-small-marker-policy.json")));
        policy.requireToken("1");
        assertThrows(IllegalArgumentException.class, () -> policy.requireToken("0"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireToken("9999"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireToken("01"));
    }

    @Test
    @DisplayName("Zmiana treści bez nowego skrótu odrzuca politykę")
    void changedContentFailsHash() throws Exception {
        var document = (ObjectNode) mapper.readTree(fixtures.resolve("k9-small-marker-policy.json").toFile());
        document.put("encoder", "another");
        assertThrows(IllegalArgumentException.class, () -> GraphMarkerPolicy.parse(document.toString()));
    }

    @Test
    @DisplayName("Niepełna grupa stanów jest odrzucona także po ponownym podpisaniu")
    void missingStateFailsAfterRehash() throws Exception {
        var document = (ObjectNode) mapper.readTree(fixtures.resolve("k9-small-marker-policy.json").toFile());
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.get("variants")).remove(0);
        var sortedMapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Map<String, Object> body = sortedMapper.convertValue(document, Map.class);
        body.remove("policySha256");
        document.put("policySha256", GraphMarkerPolicy.digest(sortedMapper.writeValueAsBytes(body)));
        var error = assertThrows(IllegalArgumentException.class, () -> GraphMarkerPolicy.parse(document.toString()));
        assertEquals("Incomplete marker states", error.getMessage());
    }

    @Test
    @DisplayName("Mapa ze znacznikami bez polityki przerywa rzeczywisty import")
    void markedMapWithoutPolicyFailsImport() throws Exception {
        try (var graph = graph()) {
            var reader = new ORSOSMReader(graph, new GraphProcessContext(new ProfileProperties()));
            reader.setFile(fixtures.resolve("k9-small-marker-produced.xml").toFile());
            var error = assertThrows(Exception.class, reader::readGraph);
            assertTrue(error.toString().contains("policy") || String.valueOf(error.getCause()).contains("policy"));
        }
    }

    @Test
    @DisplayName("Znacznik zapisany w grafie bez polityki jest odrzucony przy wczytaniu")
    void markedStoredGraphWithoutPolicyFailsValidation() {
        try (var graph = graph()) {
            graph.create(100);
            graph.getNodeAccess().setNode(0, 52, 21);
            graph.getNodeAccess().setNode(1, 52, 21.01);
            graph.edge(0, 1).set(graph.getEncodingManager().getIntEncodedValue(GraphMarkerPolicy.ENCODED_VALUE), 1);
            assertThrows(IllegalArgumentException.class, () -> GraphMarkerPolicy.validateGraph(graph));
        }
    }

    @Test
    @DisplayName("Klasyczna mapa bez polityki zachowuje pustą właściwość grafu")
    void classicMapDoesNotAcquirePolicy() {
        try (var graph = graph()) {
            GraphMarkerPolicy.loadForImport(graph, fixtures.resolve("k9-small-marker-map.xml"), "turn_costs=true", Path.of("missing.jar"), Path.of("missing.yml"));
            assertNull(GraphMarkerPolicy.fromGraph(graph));
            graph.create(100);
            GraphMarkerPolicy.validateGraph(graph);
        }
    }

    @Test
    @DisplayName("Pokwitowanie znaczników wymaga polityki nawet gdy graf nie ma wariantowanych dróg")
    void markerReceiptWithoutPolicyFails() throws Exception {
        var map = temporary.resolve("map.osm");
        mapper.writeValue(Path.of(map + ".graph-input.json").toFile(), Map.of("schema", "route-quality-graph-transform-v2"));
        try (var graph = graph()) {
            assertThrows(IllegalArgumentException.class, () -> GraphMarkerPolicy.loadForImport(graph, map, "turn_costs=true", Path.of("missing.jar"), Path.of("missing.yml")));
        }
    }

    @Test
    @DisplayName("Importer sprawdza wspólną mapę, politykę, plik JAR i konfigurację")
    void receiptBindsAllImportInputs() throws Exception {
        var map = temporary.resolve("map.osm");
        var policyFile = Path.of(map + ".marker-policy.json");
        Files.copy(fixtures.resolve("k9-small-marker-produced.xml"), map);
        Files.copy(fixtures.resolve("k9-small-marker-policy.json"), policyFile);
        var policy = mapper.readTree(policyFile.toFile());
        var jar = temporary.resolve("encoder.jar");
        var config = temporary.resolve("config.yml");
        Files.writeString(jar, "same-built-encoder");
        Files.writeString(config, "same-configuration");
        var receipt = new TreeMap<String, Object>();
        receipt.put("schema", "route-quality-graph-transform-v2");
        for (String key : new String[]{"setVersion", "snapshotSha256", "policySha256"}) receipt.put(key, policy.get(key).asText());
        receipt.put("pbfSha256", GraphMarkerPolicy.digest(Files.readAllBytes(map)));
        receipt.put("policyFileSha256", GraphMarkerPolicy.digest(Files.readAllBytes(policyFile)));
        receipt.put("encoderJarSha256", GraphMarkerPolicy.digest(Files.readAllBytes(jar)));
        receipt.put("configSha256", GraphMarkerPolicy.digest(Files.readAllBytes(config)));
        mapper.writeValue(Path.of(map + ".graph-input.json").toFile(), receipt);
        try (var graph = graph()) {
            GraphMarkerPolicy.loadForImport(graph, map, "turn_costs=true", jar, config);
            assertNotNull(GraphMarkerPolicy.fromGraph(graph));
            assertThrows(IllegalArgumentException.class, () -> GraphMarkerPolicy.loadForImport(graph, map, "turn_costs=false", jar, config));
            for (var file : new Path[]{map, policyFile, jar, config}) {
                byte[] original = Files.readAllBytes(file);
                try {
                    Files.write(file, (new String(original, java.nio.charset.StandardCharsets.UTF_8) + " ").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    assertThrows(IllegalArgumentException.class, () -> GraphMarkerPolicy.loadForImport(graph, map, "turn_costs=true", jar, config));
                } finally {
                    Files.write(file, original);
                }
            }
        }
    }
}
