package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
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
public class CustomApplicationUtils {

    private static final JsonMetaSchema dialMetaSchema = JsonMetaSchema.builder("https://dial.epam.com/custom_application_schemas/schema#",
                    JsonMetaSchema.getV7())
            .keyword(new DialMetaKeyword())
            .keyword(new DialFileKeyword())
            .build();

    private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory.builder()
            .metaSchema(dialMetaSchema)
            .defaultMetaSchemaIri(dialMetaSchema.getIri())
            .build();

    @SuppressWarnings("unchecked")
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
        ListCollector<String> propsCollector =
                (ListCollector<String>) collectorContext.getCollectorMap().get(collectorName);
        Map<String, Object> result = new HashMap<>();
        for (String propertyName : propsCollector.collect()) {
            result.put(propertyName, customProps.get(propertyName));
        }
        return result;
    }

    public static Map<String, Object> getCustomServerProperties(Config config, Application application) throws JsonProcessingException {
        String customApplicationSchema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
        if (customApplicationSchema == null) {
            return Map.of();
        }
        return filterPropertiesWithCollector(application.getCustomProperties(),
                customApplicationSchema, "server");
    }

    public static String getCustomApplicationEndpoint(Config config, Application application) throws JsonProcessingException {
        String schema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);
        JsonNode endpointNode = schemaNode.get("dial:custom-application-type-completion-endpoint");
        if (endpointNode == null) {
            throw new IllegalArgumentException("Custom application schema does not contain completion endpoint");
        }
        return endpointNode.asText();
    }

    public static Application modifyEndpointForCustomApplication(Config config, Application application) throws JsonProcessingException {
        String customEndpoint = getCustomApplicationEndpoint(config, application);
        if (customEndpoint == null) {
            return application;
        }
        Application copy = new Application(application);
        copy.setEndpoint(customEndpoint);
        return copy;
    }

    public static Application filterCustomClientProperties(Config config, Application application) throws JsonProcessingException {
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

    public static Application filterCustomClientPropertiesWhenNoWriteAccess(ProxyContext ctx, ResourceDescriptor resource,
                                                                     Application application) throws JsonProcessingException {
        if (!ctx.getProxy().getAccessService().hasWriteAccess(resource, ctx)) {
            application = filterCustomClientProperties(ctx.getConfig(), application);
        }
        return application;
    }

    @SuppressWarnings("unchecked")
    public static List<ResourceDescriptor> getFiles(Config config, Application application, EncryptionService encryptionService,
                                        ResourceService resourceService) throws JsonProcessingException {
        String customApplicationSchema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
        if (customApplicationSchema == null) {
            return List.of();
        }
        JsonSchema appSchema = schemaFactory.getSchema(customApplicationSchema);
        CollectorContext collectorContext = new CollectorContext();
        String customPropsJson = ProxyUtil.MAPPER.writeValueAsString(application.getCustomProperties());
        Set<ValidationMessage> validationResult = appSchema.validate(customPropsJson, InputFormat.JSON,
                e -> e.setCollectorContext(collectorContext));
        if (!validationResult.isEmpty()) {
            throw new IllegalArgumentException("Invalid custom properties: " + validationResult);
        }
        ListCollector<String> propsCollector =
                (ListCollector<String>) collectorContext.getCollectorMap().get("file");
        List<ResourceDescriptor> result = new ArrayList<>();
        for (String item : propsCollector.collect()) {
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
            if (!resourceService.hasResource(descriptor)) {
                throw new IllegalArgumentException("Resource not found: " + item);
            }
            result.add(descriptor);
        }
        return result;
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

    private static class DialFileKeyword implements Keyword {
        @Override
        public String getValue() {
            return "dial:file";
        }

        @Override
        public JsonValidator newValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath,
                                          JsonNode schemaNode, JsonSchema parentSchema, ValidationContext validationContext) {
            return new DialFileCollectorValidator(schemaLocation, evaluationPath, schemaNode, parentSchema, this, validationContext, false);
        }
    }

    public static class ListCollector<T> implements Collector<List<T>> {
        private final List<T> references = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public void combine(Object o) {
            if (!(o instanceof List)) {
                return;
            }
            List<T> list = (List<T>) o;
            synchronized (references) {
                references.addAll(list);
            }
        }

        @Override
        public List<T> collect() {
            return references;
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
        @SuppressWarnings("unchecked")
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {

            CollectorContext collectorContext = executionContext.getCollectorContext();
            ListCollector<String> serverPropsCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                    .computeIfAbsent("server", k -> new ListCollector<String>());
            ListCollector<String> clientPropsCollector = (ListCollector<String>) collectorContext
                    .getCollectorMap().computeIfAbsent("client", k -> new ListCollector<String>());
            String propertyName = jsonNodePath.getName(-1);
            if (Objects.equals(propertyKindString, "server")) {
                serverPropsCollector.combine(List.of(propertyName));
            } else {
                clientPropsCollector.combine(List.of(propertyName));
            }
            return Set.of();
        }
    }

    private static class DialFileCollectorValidator extends BaseJsonValidator {
        private static final ErrorMessageType ERROR_MESSAGE_TYPE = () -> "dial:file";

        private final Boolean value;

        public DialFileCollectorValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath, JsonNode schemaNode,
                                          JsonSchema parentSchema, Keyword keyword,
                                          ValidationContext validationContext, boolean suppressSubSchemaRetrieval) {
            super(schemaLocation, evaluationPath, schemaNode, parentSchema, ERROR_MESSAGE_TYPE, keyword, validationContext, suppressSubSchemaRetrieval);
            this.value = schemaNode.booleanValue();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {
            if (value) {
                CollectorContext collectorContext = executionContext.getCollectorContext();
                ListCollector<String> serverPropsCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                        .computeIfAbsent("file", k -> new ListCollector<String>());
                serverPropsCollector.combine(List.of(jsonNode.asText()));
            }
            return Set.of();
        }
    }
}