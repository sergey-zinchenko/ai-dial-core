package com.epam.aidial.core.metaschemas;

import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.NonValidationKeyword;
import lombok.experimental.UtilityClass;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@UtilityClass
public class MetaSchemaHolder {

    public static final String CUSTOM_APPLICATION_META_SCHEMA_ID = "https://dial.epam.com/application_type_schemas/schema#";
    public static final String APPLICATION_TYPE_EDITOR_URL = "dial:applicationTypeEditorUrl";
    public static final String APPLICATION_TYPE_VIEWER_URL = "dial:applicationTypeViewerUrl";
    public static final String APPLICATION_TYPE_DISPLAY_NAME = "dial:applicationTypeDisplayName";
    public static final String APPLICATION_TYPE_COMPLETION_ENDPOINT = "dial:applicationTypeCompletionEndpoint";
    public static final String APPLICATION_TYPE_CONFIGURATION_ENDPOINT = "dial:applicationTypeConfigurationEndpoint";
    public static final String PROPERTY_KIND = "dial:propertyKind";
    public static final String PROPERTY_ORDER = "dial:propertyOrder";

    public static String getCustomApplicationMetaSchema() {
        try (InputStream inputStream = MetaSchemaHolder.class.getClassLoader()
                .getResourceAsStream("custom-application-schemas/schema")) {
            assert inputStream != null;
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load custom application meta schema", e);
        }
    }

    public static JsonMetaSchema.Builder getMetaschemaBuilder() {
        return JsonMetaSchema.builder(MetaSchemaHolder.CUSTOM_APPLICATION_META_SCHEMA_ID, JsonMetaSchema.getV7())
                .keyword(new NonValidationKeyword(APPLICATION_TYPE_EDITOR_URL))
                .keyword(new NonValidationKeyword(APPLICATION_TYPE_VIEWER_URL))
                .keyword(new NonValidationKeyword(APPLICATION_TYPE_DISPLAY_NAME))
                .keyword(new NonValidationKeyword(APPLICATION_TYPE_COMPLETION_ENDPOINT))
                .keyword(new NonValidationKeyword(APPLICATION_TYPE_CONFIGURATION_ENDPOINT))
                .keyword(new NonValidationKeyword(PROPERTY_KIND))
                .keyword(new NonValidationKeyword(PROPERTY_ORDER))
                .keyword(new NonValidationKeyword("$defs"))
                .format(new DialFileFormat());
    }
}