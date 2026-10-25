package org.heigit.ors.api.services;

import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.config.profile.ServiceProperties;
import org.heigit.ors.exceptions.ServerLimitExceededException;
import org.heigit.ors.routing.RoutingProfile;
import org.heigit.ors.routing.RoutingRequest;
import org.locationtech.jts.geom.Coordinate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogicalRouteLimitsTest {
    private RoutingRequest request(double from, double to, double limit) {
        var service = new ServiceProperties();
        service.setMaximumDistance(limit);
        service.setMaximumWayPoints(50);
        var properties = new ProfileProperties();
        properties.setService(service);
        var profile = mock(RoutingProfile.class);
        when(profile.getProfileConfiguration()).thenReturn(properties);
        var request = new RoutingRequest();
        request.setRoutingProfile(profile);
        request.setCoordinates(new Coordinate[]{new Coordinate(21, from), new Coordinate(21, to)});
        return request;
    }

    @Test void splittingGroupsDoesNotResetTheDistanceLimit() {
        var first = request(52, 52.01, 1500);
        var second = request(52.01, 52.02, 1500);
        assertDoesNotThrow(() -> RoutingService.validateLogicalRouteDistance(List.of(first)));
        assertDoesNotThrow(() -> RoutingService.validateLogicalRouteDistance(List.of(second)));
        assertThrows(ServerLimitExceededException.class,
                () -> RoutingService.validateLogicalRouteDistance(List.of(first, second)));
    }

    @Test void repeatedSeamDoesNotAddDistance() {
        var first = request(52, 52.01, 1500);
        var repeated = request(52.01, 52.01, 1500);
        assertDoesNotThrow(() -> RoutingService.validateLogicalRouteDistance(List.of(first, repeated)));
    }

    @Test void legacyRequestsKeepTheirDistanceLimit() {
        assertDoesNotThrow(() -> RoutingService.validateRouteProfileForRequest(request(52, 52.01, 1500)));
        assertThrows(ServerLimitExceededException.class,
                () -> RoutingService.validateRouteProfileForRequest(request(52, 52.02, 1500)));
    }

    @Test void customModelsKeepTheFullyDynamicLimitAcrossGroups() {
        var requests = List.of(request(52, 52.01, 10_000), request(52.01, 52.02, 10_000));
        for (var request : requests) {
            request.getSearchParameters().setCustomModel(new com.graphhopper.util.CustomModel());
            request.profile().getProfileConfiguration().getService().setMaximumDistanceAvoidAreas(1500.0);
            assertDoesNotThrow(() -> RoutingService.validateRouteProfileForRequest(request));
        }
        assertThrows(ServerLimitExceededException.class, () -> RoutingService.validateLogicalRouteDistance(requests));
    }
}
