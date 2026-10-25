package org.heigit.ors.api.config;

import org.heigit.ors.api.controllers.RoutingAPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.heigit.ors.api.config.EndpointsProperties;
import org.heigit.ors.api.config.SystemMessageProperties;
import org.heigit.ors.api.requests.routing.RouteRequest;
import org.heigit.ors.api.responses.routing.RouteResponseInfo;
import org.heigit.ors.util.AppInfo;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LogicalRouteResponseTest {
    @Test void preservesOrdinaryRouteEngineIdentity() throws Exception {
        var before = AppInfo.getEngineInfo();
        String previousDate = before.optString("graph_date", null);
        try {
            AppInfo.setGraphDate("2026-10-22T20:23:14Z");
            var request = new RouteRequest(new Double[][]{{21.0, 52.0}, {21.1, 52.1}});
            var endpoints = new EndpointsProperties();
            endpoints.setRouting(new EndpointsProperties.EndpointRoutingProperties());
            var metadata = new RouteResponseInfo(request, new SystemMessageProperties(), endpoints);
            var mapper = new ObjectMapper();
            var ordinary = mapper.valueToTree(metadata).get("engine");
            var logical = mapper.valueToTree(new RoutingAPI.LogicalRouteResponse(List.of(), metadata));
            assertEquals(ordinary, logical.at("/metadata/engine"));
            for (String field : List.of("version", "build_date", "graph_date"))
                assertFalse(logical.at("/metadata/engine/" + field).asText().isEmpty(), field);
            assertEquals("2026-10-22T20:23:14Z", logical.at("/metadata/engine/graph_date").asText());
        } finally { AppInfo.setGraphDate(previousDate); }
    }
}
