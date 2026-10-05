package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.PMap;
import org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RouteQualityGraphWayEncoding {
    private RouteQualityGraphWayEncoding() {}

    public static Map<String, Object> encode(JsonNode request) {
        if (!request.path("schema").asText().equals("route-quality-graph-way-encoding-v1")
                || !request.path("ways").isArray() || !request.path("flagEncoderOptions").isTextual())
            throw new IllegalArgumentException("Invalid graph way encoding request");
        var em = new EncodingManager.Builder().add(new ORSDefaultFlagEncoderFactory()
                .createFlagEncoder(FlagEncoderNames.BUS, new PMap(request.get("flagEncoderOptions").asText()))).build();
        var access = em.getEncoder(FlagEncoderNames.BUS).getAccessEnc();
        var keys = new HashSet<String>();
        var results = new ArrayList<Map<String, Object>>();
        for (var entry : request.get("ways")) {
            if (!entry.path("key").isTextual() || !keys.add(entry.get("key").asText())
                    || !entry.path("tags").isObject() || !entry.path("nodes").isArray() || entry.get("nodes").size() < 2)
                throw new IllegalArgumentException("Invalid graph way encoding entry");
            var way = new ReaderWay(Long.parseLong(entry.path("id").asText()));
            for (var node : entry.get("nodes")) way.getNodes().add(Long.parseLong(node.asText()));
            entry.get("tags").fields().forEachRemaining(tag -> {
                if (!tag.getValue().isTextual()) throw new IllegalArgumentException("Invalid graph way tag");
                way.setTag(tag.getKey(), tag.getValue().asText());
            });
            var accepted = new EncodingManager.AcceptWay();
            boolean included = em.acceptWay(way, accepted);
            var flags = included ? em.handleWayTags(way, accepted, em.createRelationFlags()) : em.createEdgeFlags();
            var result = new LinkedHashMap<String, Object>();
            result.put("key", entry.get("key").asText());
            result.put("accepted", included);
            result.put("forward", access.getBool(false, flags));
            result.put("backward", access.getBool(true, flags));
            results.add(result);
        }
        return Map.of("schema", "route-quality-graph-way-encoding-result-v1", "encoder", em.toFlagEncodersAsString(), "ways", results);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Read a graph way request from stdin");
        byte[] input = System.in.readNBytes(32 * 1024 * 1024 + 1);
        if (input.length > 32 * 1024 * 1024) throw new IllegalArgumentException("Graph way request exceeds the byte budget");
        var mapper = new ObjectMapper();
        var response = new LinkedHashMap<>(encode(mapper.readTree(input)));
        response.put("requestSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)));
        System.out.write(mapper.writeValueAsBytes(response));
        System.out.write(10);
    }
}
