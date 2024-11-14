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
import com.networknt.schema.Format;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.JsonType;
import com.networknt.schema.JsonValidator;
import com.networknt.schema.Keyword;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.TypeFactory;
import com.networknt.schema.ValidationContext;
import com.networknt.schema.ValidationMessage;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class CustomApplicationUtils {

    private static final JsonMetaSchema dialMetaSchema = JsonMetaSchema.builder("https://dial.epam.com/custom_application_schemas/schema#",
                    JsonMetaSchema.getV7())
            .keyword(new DialMetaKeyword())
            .keyword(new DialFileKeyword())
            .format(new DialFileFormat())
            .build();

    private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory.builder()
            .metaSchema(dialMetaSchema)
            .defaultMetaSchemaIri(dialMetaSchema.getIri())
            .build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterPropertiesWithCollector(
            Map<String, Object> customProps, String schema, String collectorName) {
        try {
            JsonSchema appSchema = schemaFactory.getSchema(schema);
            CollectorContext collectorContext = new CollectorContext();
            String customPropsJson = ProxyUtil.MAPPER.writeValueAsString(customProps);
            Set<ValidationMessage> validationResult = appSchema.validate(customPropsJson, InputFormat.JSON,
                    e -> e.setCollectorContext(collectorContext));
            if (!validationResult.isEmpty()) {
                throw new CustomAppValidationException("Failed to validate custom app against the schema", validationResult);
            }
            ListCollector<String> propsCollector =
                    (ListCollector<String>) collectorContext.getCollectorMap().get(collectorName);
            if (propsCollector == null) {
                return Map.of();
            }
            Map<String, Object> result = new HashMap<>();
            for (String propertyName : propsCollector.collect()) {
                result.put(propertyName, customProps.get(propertyName));
            }
            return result;
        } catch (CustomAppValidationException e) {
            throw e;
        } catch (Throwable e) {
            throw new CustomAppValidationException("Failed to filter custom properties", e);
        }
    }

    public static Map<String, Object> getCustomServerProperties(Config config, Application application) {
        String customApplicationSchema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
        if (customApplicationSchema == null) {
            return Map.of();
        }
        return filterPropertiesWithCollector(application.getCustomProperties(),
                customApplicationSchema, "server");
    }

    public static String getCustomApplicationEndpoint(Config config, Application application) {
        try {
            String schema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
            JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);
            JsonNode endpointNode = schemaNode.get("dial:custom-application-type-completion-endpoint");
            if (endpointNode == null) {
                throw new CustomAppValidationException("Custom application schema does not contain completion endpoint");
            }
            return endpointNode.asText();
        } catch (JsonProcessingException e) {
            throw new CustomAppValidationException("Failed to get custom application endpoint", e);
        }
    }

    public static Application modifyEndpointForCustomApplication(Config config, Application application) {
        String customEndpoint = getCustomApplicationEndpoint(config, application);
        if (customEndpoint == null) {
            return application;
        }
        Application copy = new Application(application);
        copy.setEndpoint(customEndpoint);
        return copy;
    }

    public static Application filterCustomClientProperties(Config config, Application application) {
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
                                                                            Application application) {
        if (!ctx.getProxy().getAccessService().hasWriteAccess(resource, ctx)) {
            application = filterCustomClientProperties(ctx.getConfig(), application);
        }
        return application;
    }

    @SuppressWarnings("unchecked")
    public static List<String> getFiles(Config config, Application application) {
        try {
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
                throw new CustomAppValidationException("Failed to validate custom app against the schema", validationResult);
            }
            ListCollector<String> propsCollector =
                    (ListCollector<String>) collectorContext.getCollectorMap().get("file");
            return propsCollector.collect();
        } catch (CustomAppValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomAppValidationException("Failed to obtain list of files attached to the custom app", e);
        }
    }


    public static List<ResourceDescriptor> getFiles(Config config, Application application, EncryptionService encryptionService,
                                                    ResourceService resourceService) {
        List<String> files = getFiles(config, application);
        List<ResourceDescriptor> result = new ArrayList<>();
        for (String item : files) {
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
            if (!resourceService.hasResource(descriptor)) {
                throw new CustomAppValidationException("Resource listed as dependent to the application not found or inaccessable: " + item);
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


    private static class DialFileFormat implements Format {

        private static final Pattern PATTERN = Pattern.compile("files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)");

        @Override
        public boolean matches(ExecutionContext executionContext, ValidationContext validationContext, JsonNode value) {
            JsonType nodeType = TypeFactory.getValueNodeType(value, validationContext.getConfig());
            if (nodeType != JsonType.STRING) {
                return false;
            }
            String nodeValue = value.textValue();
            Matcher matcher = PATTERN.matcher(nodeValue);
            return matcher.matches();
        }

        @Override
        public String getName() {
            return "dial-file";
        }
    }
}