package org.heigit.ors.api.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.heigit.ors.api.APIEnums;
import org.heigit.ors.api.requests.routing.RouteRequest;
import org.heigit.ors.api.responses.common.engineinfo.EngineInfo;
import org.heigit.ors.api.routequality.GraphMarkerHeader;
import org.heigit.ors.api.services.RoutingService;
import org.heigit.ors.routing.RoutingProfileManager;
import org.heigit.ors.routing.RoutingErrorCodes;
import org.heigit.ors.routing.servicedirection.DirectedGraphWindow;
import org.heigit.ors.util.AppInfo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/v2/service-direction")
public class ServiceDirectionAPI {
    public static final class GraphRequest extends RouteRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("corridor_model")
        public org.heigit.ors.api.requests.routing.RouteRequestCustomModel corridorModel;
        @com.fasterxml.jackson.annotation.JsonCreator
        public GraphRequest(@com.fasterxml.jackson.annotation.JsonProperty("coordinates") java.util.List<java.util.List<Double>> coordinates) {
            super(coordinates);
        }
    }
    private final RoutingService routing;
    public ServiceDirectionAPI(RoutingService routing) { this.routing = routing; }

    @ExceptionHandler(org.heigit.ors.exceptions.StatusCodeException.class)
    public org.springframework.http.ResponseEntity<Object> handle(org.heigit.ors.exceptions.StatusCodeException error) {
        return RoutingAPI.errorHandler.handleStatusCodeException(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Object> invalid(IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", Map.of("code", 2003, "message", error.getMessage())));
    }

    @PostMapping("/{profile}/graph")
    public Map<String, Object> read(@PathVariable String profile, @RequestBody GraphRequest request,
                                    HttpServletRequest servlet) throws Exception {
        if (!profile.equals("driving-bus") || request.getCoordinates().size() != 3)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected driving-bus and three boundary locations");
        request.setProfile(APIEnums.Profile.DRIVING_BUS);
        request.setProfileName(profile);
        var rp = RoutingProfileManager.getInstance().getRoutingProfile(profile);
        if (rp == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Profile unavailable");
        request.setGraphMarkerSelection(GraphMarkerHeader.read(servlet, RoutingErrorCodes.INVALID_PARAMETER_VALUE));
        if (request.hasCustomModel() && (Boolean.FALSE.equals(rp.getProfileProperties().getBuild().getEncoderOptions().getEnableCustomModels())
                || Boolean.FALSE.equals(rp.getProfileProperties().getService().getAllowCustomModels())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custom model unavailable");
        var converted = routing.convertRouteRequest(request);
        var parameters = converted.getSearchParameters();
        parameters.setGraphMarkerSelection(GraphMarkerHeader.validate(rp.getGraphhopper().getGraphHopperStorage(),
                request.getGraphMarkerSelection(), RoutingErrorCodes.INVALID_PARAMETER_VALUE));
        if (parameters.isTimeDependent()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Time dependent graphs are unsupported");
        var window = DirectedGraphWindow.read(rp, parameters, converted.getCoordinates(),
                request.corridorModel == null ? null : request.corridorModel.toGHCustomModel(), 2005,
                () -> Thread.currentThread().isInterrupted());
        var engine = AppInfo.getEngineInfo();
        engine.put("graph_date", window.graphDate());
        return Map.of("metadata", Map.of("engine", new EngineInfo(engine)), "window", window);
    }
}
