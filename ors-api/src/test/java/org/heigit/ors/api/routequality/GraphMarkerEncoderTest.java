package org.heigit.ors.api.routequality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GraphMarkerEncoderTest {
    @TempDir Path temporary;

    @Test
    @DisplayName("Enkoder odczytuje opcje produkcji tą samą regułą wiązania co ORS")
    void productionOptionsUseNativeBinding() throws Exception {
        assertEquals("turn_costs=true|block_fords=false|use_acceleration=false|enable_custom_models=true",
                GraphMarkerEncoder.options(Path.of("../ors-docker/config/prod-ors-config.yml")));
    }

    @Test
    @DisplayName("Opcje profilu nadpisują opcje domyślne i dziedziczą pozostałe")
    void profileOverridesDefaults() throws Exception {
        var config = temporary.resolve("config.yml");
        Files.writeString(config, """
                ors:
                  engine:
                    profile_default:
                      build:
                        encoder_options:
                          turn_costs: true
                          block_fords: true
                          enable_custom_models: true
                    profiles:
                      driving-bus:
                        enabled: true
                        encoder_name: driving-bus
                        build:
                          encoder_options:
                            block_fords: false
                """);
        assertEquals("turn_costs=true|block_fords=false|enable_custom_models=true", GraphMarkerEncoder.options(config));
    }

    @Test
    @DisplayName("Profil bez kosztów skrętu nie może budować grafu ze znacznikami")
    void missingTurnCostsFails() throws Exception {
        var config = temporary.resolve("invalid.yml");
        Files.writeString(config, """
                ors:
                  engine:
                    profiles:
                      driving-bus:
                        enabled: true
                        encoder_name: driving-bus
                        build:
                          encoder_options:
                            turn_costs: false
                            enable_custom_models: true
                """);
        assertThrows(IllegalArgumentException.class, () -> GraphMarkerEncoder.options(config));
    }
}
