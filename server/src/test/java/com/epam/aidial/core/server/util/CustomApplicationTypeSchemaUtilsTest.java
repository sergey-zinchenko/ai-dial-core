package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CustomApplicationTypeSchemaUtilsTest {
    private Config config;
    private Application application;

    @BeforeEach
    void setUp() {
        config = mock(Config.class);
        application = mock(Application.class);
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
    public void getCustomApplicationSchemaOrThrow_throwsException_whenSchemaNotFound() {
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
        String schema = "{"
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
        Map<String, Object> clientProperties = Map.of("clientFile",
                "files/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/valid-file-path/valid-sub-path/valid%20file%20name1.ext");
        Map<String, Object> serverProperties = Map.of(
                "serverFile",
                "files/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/valid-file-path/valid-sub-path/valid%20file%20name2.ext");
        Map<String, Object> customProperties = new HashMap<>();
        customProperties.putAll(clientProperties);
        customProperties.putAll(serverProperties);
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

}
