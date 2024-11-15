package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.validation.DialFileFormat;
import com.epam.aidial.core.server.validation.DialFileKeyword;
import com.epam.aidial.core.server.validation.DialMetaKeyword;
import com.epam.aidial.core.server.validation.ListCollector;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@UtilityClass
public class CustomApplicationUtils {

    private static final JsonMetaSchema DIAL_META_SCHEMA = JsonMetaSchema.builder("https://dial.epam.com/custom_application_schemas/schema#",
                    JsonMetaSchema.getV7())
            .keyword(new DialMetaKeyword())
            .keyword(new DialFileKeyword())
            .format(new DialFileFormat())
            .build();

    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.builder()
            .metaSchema(DIAL_META_SCHEMA)
            .defaultMetaSchemaIri(DIAL_META_SCHEMA.getIri())
            .build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterProperties(Map<String, Object> customProps, String schema, String collectorName) {
        try {
            JsonSchema appSchema = SCHEMA_FACTORY.getSchema(schema);
            CollectorContext collectorContext = new CollectorContext();
            String customPropsJson = ProxyUtil.MAPPER.writeValueAsString(customProps);
            Set<ValidationMessage> validationResult = appSchema.validate(customPropsJson, InputFormat.JSON,
                    e -> e.setCollectorContext(collectorContext));
            if (!validationResult.isEmpty()) {
                throw new CustomAppValidationException("Failed to validate custom app against the schema", validationResult);
            }
            ListCollector<String> propsCollector = (ListCollector<String>) collectorContext.getCollectorMap().get(collectorName);
            if (propsCollector == null) {
                return Collections.emptyMap();
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
            return Collections.emptyMap();
        }
        return filterProperties(application.getCustomProperties(), customApplicationSchema, "server");
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
        Map<String, Object> appWithClientOptionsOnly = filterProperties(application.getCustomProperties(), customApplicationSchema, "client");
        copy.setCustomProperties(appWithClientOptionsOnly);
        return copy;
    }

    public static Application filterCustomClientPropertiesWhenNoWriteAccess(ProxyContext ctx, ResourceDescriptor resource, Application application) {
        if (!ctx.getProxy().getAccessService().hasWriteAccess(resource, ctx)) {
            application = filterCustomClientProperties(ctx.getConfig(), application);
        }
        return application;
    }

    @SuppressWarnings("unchecked")
    public static List<ResourceDescriptor> getFiles(Config config, Application application, EncryptionService encryptionService, ResourceService resourceService) {
        try {
            String customApplicationSchema = config.getCustomApplicationSchema(application.getCustomAppSchemaId());
            if (customApplicationSchema == null) {
                return Collections.emptyList();
            }
            JsonSchema appSchema = SCHEMA_FACTORY.getSchema(customApplicationSchema);
            CollectorContext collectorContext = new CollectorContext();
            String customPropsJson = ProxyUtil.MAPPER.writeValueAsString(application.getCustomProperties());
            Set<ValidationMessage> validationResult = appSchema.validate(customPropsJson, InputFormat.JSON,
                    e -> e.setCollectorContext(collectorContext));
            if (!validationResult.isEmpty()) {
                throw new CustomAppValidationException("Failed to validate custom app against the schema", validationResult);
            }
            ListCollector<String> propsCollector = (ListCollector<String>) collectorContext.getCollectorMap().get("file");
            List<ResourceDescriptor> result = new ArrayList<>();
            for (String item : propsCollector.collect()) {
                ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
                if (!resourceService.hasResource(descriptor)) {
                    throw new CustomAppValidationException("Resource listed as dependent to the application not found or inaccessible: " + item);
                }
                result.add(descriptor);
            }
            return result;
        } catch (CustomAppValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomAppValidationException("Failed to obtain list of files attached to the custom app", e);
        }
    }
}