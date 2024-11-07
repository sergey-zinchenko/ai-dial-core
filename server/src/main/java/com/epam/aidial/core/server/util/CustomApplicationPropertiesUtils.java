package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.BaseJsonValidator;
import com.networknt.schema.Collector;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.ErrorMessageType;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.JsonValidator;
import com.networknt.schema.Keyword;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.ValidationContext;
import com.networknt.schema.ValidationMessage;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@UtilityClass
public class CustomApplicationPropertiesUtils {

    private static final JsonMetaSchema dialMetaSchema = JsonMetaSchema.builder("https://dial.epam.com/custom_application_schemas/schema#",
                    JsonMetaSchema.getV7())
            .keyword(new DialMetaKeyword())
            .build();

    private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory.builder()
            .metaSchema(dialMetaSchema)
            .defaultMetaSchemaIri(dialMetaSchema.getIri())
            .build();

    private static Map<String, Object> filterPropertiesWithCollector(
            Map<String, Object> customProps, String schema, String collectorName) throws JsonProcessingException {
        JsonSchema appSchema = schemaFactory.getSchema(schema);
        CollectorContext collectorContext = new CollectorContext();
        String customPropsJson = ProxyUtil.MAPPER.writeValueAsString(customProps);
        Set<ValidationMessage> validationResult = appSchema.validate(customPropsJson, InputFormat.JSON,
                e -> e.setCollectorContext(collectorContext));
        if (!validationResult.isEmpty()) {
            throw new IllegalArgumentException("Invalid custom properties: " + validationResult);
        }
        DialMetaCollectorValidator.StringStringMapCollector propsCollector =
                (DialMetaCollectorValidator.StringStringMapCollector) collectorContext.getCollectorMap().get(collectorName);
        Map<String, Object> result = new HashMap<>();
        for (String propertyName : propsCollector.collect()) {
            result.put(propertyName, customProps.get(propertyName));
        }
        return result;
    }

    public Map<String, Object> getCustomServerProperties(Config config, Application application) throws JsonProcessingException {
        String customApplicationSchema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
        if (customApplicationSchema == null) {
            return Map.of();
        }
        return filterPropertiesWithCollector(application.getCustomProperties(),
                customApplicationSchema, "server");
    }

    public Application filterCustomClientProperties(Config config, Application application) throws JsonProcessingException {
        String customApplicationSchema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
        if (customApplicationSchema == null) {
            return application;
        }
        Application copy = new Application(application);
        Map<String, Object> appWithClientOptionsOnly = filterPropertiesWithCollector(application.getCustomProperties(),
                customApplicationSchema, "client");
        copy.setCustomProperties(appWithClientOptionsOnly);
        return copy;
    }

    public Application filterCustomClientPropertiesWhenNoWriteAccess(ProxyContext ctx, ResourceDescriptor resource,
                                                                     Application application) throws JsonProcessingException {
        Proxy proxy = ctx.getProxy();
        if (!proxy.getAccessService().hasWriteAccess(resource, ctx)) {
            application = CustomApplicationPropertiesUtils.filterCustomClientProperties(ctx.getConfig(), application);
        }
        return application;
    }

    private static class DialMetaKeyword implements Keyword {
        @Override
        public String getValue() {
            return "dial:meta";
        }

        @Override
        public JsonValidator newValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath,
                                          JsonNode schemaNode, JsonSchema parentSchema, ValidationContext validationContext) {
            return new DialMetaCollectorValidator(schemaLocation, evaluationPath, schemaNode, parentSchema, this, validationContext, false);
        }
    }


    private static class DialMetaCollectorValidator extends BaseJsonValidator {
        private static final ErrorMessageType ERROR_MESSAGE_TYPE = () -> "dial:meta";

        String propertyKindString;

        public DialMetaCollectorValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath, JsonNode schemaNode,
                                          JsonSchema parentSchema, Keyword keyword,
                                          ValidationContext validationContext, boolean suppressSubSchemaRetrieval) {
            super(schemaLocation, evaluationPath, schemaNode, parentSchema, ERROR_MESSAGE_TYPE, keyword, validationContext, suppressSubSchemaRetrieval);
            propertyKindString = schemaNode.get("dial:property-kind").asText();
        }

        @Override
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {

            CollectorContext collectorContext = executionContext.getCollectorContext();
            StringStringMapCollector serverPropsCollector = (StringStringMapCollector) collectorContext.getCollectorMap()
                    .computeIfAbsent("server", k -> new StringStringMapCollector());
            StringStringMapCollector clientPropsCollector = (StringStringMapCollector) collectorContext
                    .getCollectorMap().computeIfAbsent("client", k -> new StringStringMapCollector());
            String propertyName = jsonNodePath.getName(-1);
            if (Objects.equals(propertyKindString, "server")) {
                serverPropsCollector.combine(List.of(propertyName));
            } else {
                clientPropsCollector.combine(List.of(propertyName));
            }
            return Set.of();
        }

        public static class StringStringMapCollector implements Collector<List<String>> {
            private final List<String> references = new ArrayList<>();

            @Override
            @SuppressWarnings("unchecked")
            public void combine(Object o) {
                if (!(o instanceof List)) {
                    return;
                }
                List<String> list = (List<String>) o;
                synchronized (references) {
                    references.addAll(list);
                }
            }

            @Override
            public List<String> collect() {
                return references;
            }
        }
    }
}