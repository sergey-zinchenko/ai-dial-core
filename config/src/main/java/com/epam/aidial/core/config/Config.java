package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.JsonArrayToSchemaMapDeserializer;
import com.epam.aidial.core.config.databind.JsonSchemaMapToJsonArraySerializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import validation.ConformToSchema;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    public static final String ASSISTANT = "assistant";

    // maintain the order of routes defined in the config
    private LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
    private Map<String, Model> models = Map.of();
    private Map<String, Addon> addons = Map.of();
    @ConformToSchema(schemaId = SchemaIdFunction.class)
    private Map<String, Application> applications = Map.of();
    private Assistants assistant = new Assistants();
    private Map<String, Key> keys = new HashMap<>();
    private Map<String, Role> roles = new HashMap<>();
    private Set<Integer> retriableErrorCodes = Set.of();
    private Map<String, Interceptor> interceptors = Map.of();

    @JsonDeserialize(using = JsonArrayToSchemaMapDeserializer.class)
    @JsonSerialize(using = JsonSchemaMapToJsonArraySerializer.class)
    @JsonProperty("custom_application_schemas")
    private Map<URI, String> customApplicationSchemas = Map.of();


    public Deployment selectDeployment(String deploymentId) {
        Application application = applications.get(deploymentId);
        if (application != null) {
            return application;
        }

        Model model = models.get(deploymentId);
        if (model != null) {
            return model;
        }

        Assistants assistants = assistant;
        return assistants.getAssistants().get(deploymentId);
    }

    static class SchemaIdFunction implements java.util.function.Function<Object, String> {
        @Override
        public String apply(Object o) {
            assert o instanceof Application;
            Application application = (Application) o;
            assert application.getCustomAppSchemaId() != null;
            return application.getCustomAppSchemaId().toString();
        }
    }
}
