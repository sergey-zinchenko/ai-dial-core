package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ApplicationTypeSchemaUtilsTest {
    private Config config;
    private Application application;
    private ProxyContext ctx;
    private ResourceDescriptor resource;
    private AccessService accessService;

    private final String schema = "{"
            + "\"$schema\": \"https://dial.epam.com/application_type_schemas/schema#\","
            + "\"$id\": \"https://mydial.epam.com/custom_application_schemas/specific_application_type\","
            + "\"dial:applicationTypeEditorUrl\": \"https://mydial.epam.com/specific_application_type_editor\","
            + "\"dial:applicationTypeDisplayName\": \"Specific Application Type\","
            + "\"dial:applicationTypeCompletionEndpoint\": \"http://specific_application_service/opeani/v1/completion\","
            + "\"properties\": {"
            + "  \"clientFile\": {"
            + "    \"type\": \"string\","
            + "    \"format\": \"dial-file-encoded\","
            + "    \"dial:meta\": {"
            + "      \"dial:propertyKind\": \"client\","
            + "      \"dial:propertyOrder\": 1"
            + "    }"
            + "  },"
            + "  \"serverFile\": {"
            + "    \"type\": \"string\","
            + "    \"format\": \"dial-file-encoded\","
            + "    \"dial:meta\": {"
            + "      \"dial:propertyKind\": \"server\","
            + "      \"dial:propertyOrder\": 2"
            + "    }"
            + "  }"
            + "},"
            + "\"required\": [\"clientFile\",\"serverFile\"]"
            + "}";

    private final Map<String, Object> clientProperties = Map.of("clientFile",
            "files/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
    private final Map<String, Object> serverProperties = Map.of(
            "serverFile",
            "files/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/valid-file-path/valid-sub-path/valid%20file%20name2.ext");
    private final Map<String, Object> customProperties = new HashMap<>();

    ApplicationTypeSchemaUtilsTest() {
        customProperties.putAll(clientProperties);
        customProperties.putAll(serverProperties);
    }

    @BeforeEach
    void setUp() {
        config = mock(Config.class);
        application = mock(Application.class);
        ctx = mock(ProxyContext.class);
        resource = mock(ResourceDescriptor.class);
        Proxy proxy = mock(Proxy.class);
        accessService = mock(AccessService.class);
        when(ctx.getProxy()).thenReturn(proxy);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(ctx.getConfig()).thenReturn(config);
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_returnsSchema_whenSchemaIdExists() {
        URI schemaId = URI.create("schemaId");
        when(application.getCustomAppSchemaId()).thenReturn(schemaId);
        when(config.getCustomApplicationSchema(schemaId)).thenReturn("schema");

        String result = ApplicationTypeSchemaUtils.getCustomApplicationSchemaOrThrow(config, application);

        Assertions.assertEquals("schema", result);
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_throws_whenSchemaNotFound() {
        URI schemaId = URI.create("schemaId");
        when(application.getCustomAppSchemaId()).thenReturn(schemaId);
        when(config.getCustomApplicationSchema(schemaId)).thenReturn(null);

        assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                ApplicationTypeSchemaUtils.getCustomApplicationSchemaOrThrow(config, application));
    }

    @Test
    public void getCustomApplicationSchemaOrThrow_returnsNull_whenSchemaIdIsNull() {
        when(application.getCustomAppSchemaId()).thenReturn(null);

        String result = ApplicationTypeSchemaUtils.getCustomApplicationSchemaOrThrow(config, application);

        Assertions.assertNull(result);
    }

    @Test
    public void getCustomServerProperties_returnsProperties_whenSchemaExists() {
        when(application.getCustomAppSchemaId()).thenReturn(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        when(application.getCustomProperties()).thenReturn(customProperties);

        Map<String, Object> result = ApplicationTypeSchemaUtils.getCustomServerProperties(config, application);

        Assertions.assertEquals(serverProperties, result);
    }

    @Test
    public void getCustomServerProperties_returnsEmptyMap_whenSchemaIsNull() {
        when(application.getCustomAppSchemaId()).thenReturn(null);

        Map<String, Object> result = ApplicationTypeSchemaUtils.getCustomServerProperties(config, application);

        Assertions.assertEquals(Collections.emptyMap(), result);
    }

    @Test
    public void getCustomServerProperties_throws_whenSchemaNotFound() {
        when(application.getCustomAppSchemaId()).thenReturn(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(null);

        Assertions.assertThrows(ApplicationTypeSchemaValidationException.class, () ->
                ApplicationTypeSchemaUtils.getCustomServerProperties(config, application));
    }


    @Test
    public void filterCustomClientProperties_returnsFilteredProperties_whenSchemaExists() {
        when(application.getCustomAppSchemaId()).thenReturn(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);
        when(application.getCustomProperties()).thenReturn(customProperties);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientProperties(config, application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals(clientProperties, result.getCustomProperties());
    }

    @Test
    public void filterCustomClientProperties_returnsOriginalApplication_whenSchemaIsNull() {
        when(application.getCustomAppSchemaId()).thenReturn(null);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientProperties(config, application);

        Assertions.assertSame(application, result);
        Assertions.assertEquals(application, result);
    }

    @Test
    public void filterCustomClientPropertiesWhenNoWriteAccess_returnsFilteredProperties_whenNoWriteAccess() {
        when(application.getCustomAppSchemaId()).thenReturn(URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type"));
        when(application.getCustomProperties()).thenReturn(customProperties);
        when(config.getCustomApplicationSchema(eq(URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type")))).thenReturn(schema);
        when(accessService.hasWriteAccess(resource, ctx)).thenReturn(false);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientPropertiesWhenNoWriteAccess(ctx, resource, application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals(clientProperties, result.getCustomProperties());
    }

    @Test
    public void filterCustomClientPropertiesWhenNoWriteAccess_returnsOriginalApplication_whenHasWriteAccess() {
        when(accessService.hasWriteAccess(resource, ctx)).thenReturn(true);
        when(application.getCustomAppSchemaId()).thenReturn(URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type"));
        when(application.getCustomProperties()).thenReturn(customProperties);
        when(config.getCustomApplicationSchema(eq(URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type")))).thenReturn(schema);

        Application result = ApplicationTypeSchemaUtils.filterCustomClientPropertiesWhenNoWriteAccess(ctx, resource, application);

        Assertions.assertSame(application, result);
        Assertions.assertEquals(customProperties, result.getCustomProperties());
    }

    @Test
    public void modifyEndpointForCustomApplication_setsCustomEndpoint_whenSchemaExists() {
        when(application.getCustomAppSchemaId()).thenReturn(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schema);

        Application result = ApplicationTypeSchemaUtils.modifyEndpointForCustomApplication(config, application);

        Assertions.assertNotSame(application, result);
        Assertions.assertEquals("http://specific_application_service/opeani/v1/completion", result.getEndpoint());
    }

    @Test
    public void modifyEndpointForCustomApplication_throws_whenSchemaIsNull() {
        when(application.getCustomAppSchemaId()).thenReturn(null);

        Assertions.assertThrows(ApplicationTypeSchemaProcessingException.class,
                () -> ApplicationTypeSchemaUtils.modifyEndpointForCustomApplication(config, application));
    }

    @Test
    public void modifyEndpointForCustomApplication_throws_whenEndpointNotFound() {
        String schemaWithoutEndpoint = "{"
                + "\"$schema\": \"https://dial.epam.com/application_type_schemas/schema#\","
                + "\"$id\": \"https://mydial.epam.com/custom_application_schemas/specific_application_type\","
                + "\"properties\": {"
                + "  \"clientFile\": {"
                + "    \"type\": \"string\","
                + "    \"format\": \"dial-file-encoded\","
                + "    \"dial:meta\": {"
                + "      \"dial:propertyKind\": \"client\","
                + "      \"dial:propertyOrder\": 1"
                + "    }"
                + "  }"
                + "},"
                + "\"required\": [\"clientFile\"]"
                + "}";
        when(application.getCustomAppSchemaId()).thenReturn(URI.create("schemaId"));
        when(config.getCustomApplicationSchema(any())).thenReturn(schemaWithoutEndpoint);

        Assertions.assertThrows(ApplicationTypeSchemaProcessingException.class, () ->
                ApplicationTypeSchemaUtils.modifyEndpointForCustomApplication(config, application));
    }

}
