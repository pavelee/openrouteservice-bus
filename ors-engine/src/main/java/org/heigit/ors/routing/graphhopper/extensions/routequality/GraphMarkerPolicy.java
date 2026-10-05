package org.heigit.ors.routing.graphhopper.extensions.routequality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.PMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class GraphMarkerPolicy {
    public static final String PROPERTY = "route_quality.marker_policy";
    public static final String ENCODED_VALUE = "bus$quality_variant";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonNode document;
    private final Set<Integer> tokens;

    private GraphMarkerPolicy(JsonNode document, Set<Integer> tokens) {
        this.document = document;
        this.tokens = Set.copyOf(tokens);
    }

    public static GraphMarkerPolicy parse(String json) {
        try {
            var document = MAPPER.readTree(json);
            if (document == null || !document.isObject() || !document.path("schema").asText().equals("route-quality-graph-marker-policy-v1"))
                throw new IllegalArgumentException("Invalid graph marker policy");
            var body = document.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("policySha256");
            if (!digest(MAPPER.writeValueAsBytes(canonical(body))).equals(document.path("policySha256").asText()))
                throw new IllegalArgumentException("Graph marker policy fingerprint differs");
            if (!document.path("setVersion").asText().matches("rq-graph-v2:[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid marker set version");
            for (String key : List.of("snapshotSha256", "encodingRequestSha256"))
                if (!document.path(key).asText().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid marker fingerprint");
            for (String key : List.of("flagEncoderOptions", "encoder"))
                if (!document.path(key).isTextual() || document.get(key).asText().isBlank()) throw new IllegalArgumentException("Missing marker encoder");
            var official = ids(document.get("officialInterventionIds"));
            var quality = ids(document.get("qualityInterventionIds"));
            if (!Collections.disjoint(official, quality) || !quality.containsAll(ids(document.get("rebuildOnlyInterventionIds"))))
                throw new IllegalArgumentException("Invalid marker channels");
            var variants = document.get("variants");
            if (variants == null || !variants.isArray()) throw new IllegalArgumentException("Missing marker variants");
            Set<Integer> tokens = new HashSet<>();
            Map<List<Long>, Set<List<Long>>> groups = new HashMap<>();
            for (var variant : variants) {
                var token = variant.get("token");
                if (token == null || !token.canConvertToInt() || !token.isIntegralNumber() || token.asInt() <= 0 || !tokens.add(token.asInt()))
                    throw new IllegalArgumentException("Invalid marker token");
                var dependencies = ids(variant.get("interventionIds"));
                var active = ids(variant.get("activeInterventionIds"));
                if (!quality.containsAll(dependencies) || !dependencies.containsAll(active) || dependencies.size() > 12)
                    throw new IllegalArgumentException("Invalid marker dependencies");
                if (!groups.computeIfAbsent(dependencies, ignored -> new HashSet<>()).add(active))
                    throw new IllegalArgumentException("Duplicate marker state");
            }
            for (var group : groups.entrySet()) if (group.getValue().size() != 1 << group.getKey().size())
                throw new IllegalArgumentException("Incomplete marker states");
            return new GraphMarkerPolicy(document, tokens);
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid graph marker policy JSON", error);
        }
    }

    private static List<Long> ids(JsonNode values) {
        if (values == null || !values.isArray()) throw new IllegalArgumentException("Invalid marker identities");
        List<Long> result = new ArrayList<>();
        long previous = 0;
        for (var value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= previous || value.asLong() > 9007199254740991L)
                throw new IllegalArgumentException("Marker identities must be positive, sorted and unique");
            previous = value.asLong();
            result.add(previous);
        }
        return List.copyOf(result);
    }

    private static Object canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            value.fields().forEachRemaining(entry -> result.put(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String fileDigest(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static boolean requiresPolicy(Path map) {
        if (Files.exists(Path.of(map + ".marker-policy.json"))) return true;
        var receipt = Path.of(map + ".graph-input.json");
        if (!Files.exists(receipt)) return false;
        try {
            return MAPPER.readTree(receipt.toFile()).path("schema").asText().equals("route-quality-graph-transform-v2");
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read graph marker receipt", error);
        }
    }

    public static void loadForImport(GraphHopperStorage graph, Path map, String encoderOptions, Path jar, Path config) {
        var file = Path.of(map + ".marker-policy.json");
        if (!requiresPolicy(map)) return;
        if (!Files.exists(file)) throw new IllegalArgumentException("Marker receipt has no policy file");
        try {
            var policy = parse(Files.readString(file));
            var receipt = MAPPER.readTree(Path.of(map + ".graph-input.json").toFile());
            if (!receipt.path("schema").asText().equals("route-quality-graph-transform-v2"))
                throw new IllegalArgumentException("Marker graph requires a versioned receipt");
            for (String key : List.of("setVersion", "snapshotSha256", "policySha256"))
                if (!policy.document.get(key).equals(receipt.get(key))) throw new IllegalArgumentException("Marker receipt belongs to another policy");
            if (!fileDigest(map).equals(receipt.path("pbfSha256").asText())
                    || !fileDigest(file).equals(receipt.path("policyFileSha256").asText())
                    || !fileDigest(jar).equals(receipt.path("encoderJarSha256").asText())
                    || !fileDigest(config).equals(receipt.path("configSha256").asText())
                    || !encoderOptions.equals(policy.document.path("flagEncoderOptions").asText()))
                throw new IllegalArgumentException("Marker map, encoder or configuration differs from its receipt");
            graph.getProperties().put(PROPERTY, policy.document.toString());
            fromGraph(graph);
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read graph marker inputs", error);
        }
    }

    public static GraphMarkerPolicy fromGraph(GraphHopperStorage graph) {
        var json = graph.getProperties().get(PROPERTY);
        if (json.isEmpty()) return null;
        var policy = parse(json);
        if (!graph.getEncodingManager().hasEncodedValue(ENCODED_VALUE)
                || !policy.document.path("encoder").asText().equals(graph.getEncodingManager().toFlagEncodersAsString()))
            throw new IllegalArgumentException("Marker policy belongs to another graph encoder");
        return policy;
    }

    public Set<Integer> selectedTokens(GraphMarkerSelection selection) {
        if (selection.policySha256() != null && !selection.policySha256().equals(document.path("policySha256").asText()))
            throw new IllegalArgumentException("Graph selection belongs to another marker policy");
        var disabled = new HashSet<>(selection.disabledInterventionIds());
        if (!new HashSet<>(ids(document.get("qualityInterventionIds"))).containsAll(disabled))
            throw new IllegalArgumentException("Graph selection can disable only declared quality interventions");
        if (!Collections.disjoint(disabled, ids(document.get("rebuildOnlyInterventionIds"))))
            throw new IllegalArgumentException("Selected intervention requires graph rebuild");
        Set<Integer> selected = new HashSet<>();
        for (var variant : document.get("variants")) {
            var active = new HashSet<>(ids(variant.get("activeInterventionIds")));
            boolean matches = true;
            for (long identity : ids(variant.get("interventionIds")))
                if (active.contains(identity) != (selection.mode().equals("ON") && !disabled.contains(identity))) matches = false;
            if (matches) selected.add(variant.get("token").asInt());
        }
        return Set.copyOf(selected);
    }

    public static void installSelection(GraphHopperStorage graph, PMap hints, GraphMarkerSelection selection) {
        var policy = fromGraph(graph);
        if (policy == null) {
            if (selection != null && selection.policySha256() != null)
                throw new IllegalArgumentException("Pinned graph selection requires a marker graph");
            return;
        }
        if (selection == null) selection = new GraphMarkerSelection("ON", policy.document.path("policySha256").asText(), List.of());
        policy.selectedTokens(selection);
        hints.putObject(GraphMarkerSelection.HINT, selection);
    }

    public void requireToken(String token) {
        if (!token.matches("[1-9][0-9]*") || !tokens.contains(Integer.parseInt(token)))
            throw new IllegalArgumentException("Unknown graph marker token");
    }

    public static void validateGraph(GraphHopperStorage graph) {
        var policy = fromGraph(graph);
        if (!graph.getEncodingManager().hasEncodedValue(ENCODED_VALUE)) return;
        var encoded = graph.getEncodingManager().getIntEncodedValue(ENCODED_VALUE);
        var edges = graph.getAllEdges();
        while (edges.next()) {
            int token = edges.get(encoded);
            if (token == 0) continue;
            if (policy == null) throw new IllegalArgumentException("Marker graph has no policy");
            policy.requireToken(Integer.toString(token));
        }
    }
}
