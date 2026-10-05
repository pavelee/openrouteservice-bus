package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteQualityGraphWayEncodingTest {
    @Test
    @DisplayName("Rozpoznanie do budowy grafu używa rzeczywistego dostępu i kierunku enkodera autobusu")
    void realEncoderReportsAcceptanceAndDirection() throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readTree("""
                {"schema":"route-quality-graph-way-encoding-v1","flagEncoderOptions":"turn_costs=true","ways":[
                {"key":"building","id":"1","nodes":["1","2"],"tags":{"building":"yes"}},
                {"key":"forward","id":"2","nodes":["1","2"],"tags":{"highway":"residential","oneway":"yes"}},
                {"key":"reverse","id":"3","nodes":["1","2"],"tags":{"highway":"residential","oneway":"-1"}},
                {"key":"private","id":"4","nodes":["1","2"],"tags":{"highway":"residential","access":"private"}},
                {"key":"official","id":"5","nodes":["1","2"],"tags":{"highway":"residential","access":"private","psv":"yes"}}
                ]}
                """);
        var result = mapper.valueToTree(RouteQualityGraphWayEncoding.encode(request)).get("ways");
        assertFalse(result.get(0).get("accepted").asBoolean());
        assertTrue(result.get(1).get("forward").asBoolean());
        assertFalse(result.get(1).get("backward").asBoolean());
        assertFalse(result.get(2).get("forward").asBoolean());
        assertTrue(result.get(2).get("backward").asBoolean());
        assertFalse(result.get(3).get("accepted").asBoolean());
        assertTrue(result.get(4).get("accepted").asBoolean());
        assertTrue(result.get(4).get("forward").asBoolean());
        assertTrue(result.get(4).get("backward").asBoolean());
    }
    @Test
    @DisplayName("Zamrożone rozpoznanie do producenta pochodzi z rzeczywistego enkodera")
    void frozenRequestAndResultsMatchActualEncoder() throws Exception {
        var mapper = new ObjectMapper();
        var fixtures = Path.of("../script/test-fixtures");
        var request = mapper.readTree(fixtures.resolve("k9-small-marker-encoding-request.json").toFile());
        var expected = mapper.readTree(fixtures.resolve("k9-small-marker-encoding.json").toFile());
        assertEquals(expected.get("requestSha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(fixtures.resolve("k9-small-marker-encoding-request.json")))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected).remove("requestSha256");
        assertEquals(expected, mapper.valueToTree(RouteQualityGraphWayEncoding.encode(request)));
    }

}
