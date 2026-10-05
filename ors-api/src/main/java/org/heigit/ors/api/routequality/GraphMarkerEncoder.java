package org.heigit.ors.api.routequality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.heigit.ors.common.EncoderNameEnum;
import org.heigit.ors.config.EngineProperties;
import org.heigit.ors.routing.graphhopper.extensions.flagencoders.RouteQualityGraphWayEncoding;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;

public final class GraphMarkerEncoder {
    private GraphMarkerEncoder() {}

    public static String options(Path config) throws Exception {
        var loader = new YamlPropertySourceLoader();
        var sources = new MutablePropertySources();
        for (var source : loader.load("marker-config", new FileSystemResource(config))) sources.addLast(source);
        for (var source : loader.load("ors-defaults", new ClassPathResource("application.yml"))) sources.addLast(source);
        var engine = new Binder(ConfigurationPropertySources.from(sources)).bind("ors.engine", Bindable.of(EngineProperties.class)).get();
        var profile = engine.getProfiles().get("driving-bus");
        if (profile == null) throw new IllegalArgumentException("Missing driving-bus profile");
        profile.mergeDefaults(engine.getProfileDefault(), "driving-bus");
        if (!Boolean.TRUE.equals(profile.getEnabled()) || profile.getEncoderName() != EncoderNameEnum.DRIVING_BUS
                || !Boolean.TRUE.equals(profile.getBuild().getEncoderOptions().getTurnCosts())
                || !Boolean.TRUE.equals(profile.getBuild().getEncoderOptions().getEnableCustomModels()))
            throw new IllegalArgumentException("Graph markers require the enabled bus encoder with turn costs and custom models");
        return profile.getBuild().getEncoderOptionsString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--options") && !args[0].equals("--encode"))
            throw new IllegalArgumentException("Use --options or --encode with an explicit ORS config");
        var options = options(Path.of(args[1]));
        var mapper = new ObjectMapper();
        if (args[0].equals("--options")) {
            System.out.write(mapper.writeValueAsBytes(java.util.Map.of("schema", "route-quality-graph-encoder-config-v1", "flagEncoderOptions", options)));
        } else {
            byte[] input = System.in.readNBytes(32 * 1024 * 1024 + 1);
            if (input.length > 32 * 1024 * 1024) throw new IllegalArgumentException("Graph way request exceeds the byte budget");
            if (!mapper.readTree(input).path("flagEncoderOptions").asText().equals(options))
                throw new IllegalArgumentException("Graph way request differs from the bound ORS configuration");
            System.out.write(mapper.writeValueAsBytes(RouteQualityGraphWayEncoding.response(input)));
        }
        System.out.write(10);
    }
}
