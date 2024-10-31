package com.epam.aidial.core.config.databind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsonArrayToSchemaMapDeserializer extends JsonDeserializer<Map<URI, String>> {

    @Override
    public Map<URI, String> deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        TreeNode tree = jsonParser.readValueAsTree();
        if (!tree.isArray()) {
            throw InvalidFormatException.from(jsonParser, "Expected a JSON array of schemas", tree.toString(), Map.class);
        }
        Map<URI, String> result = Map.of();
        for (int i = 0; i < tree.size(); i++) {
            TreeNode value = tree.get(i);
            if (!value.isObject()) {
                continue;
            }
            JsonNode valueNode = (JsonNode) value;
            if (!valueNode.has("$id")) {
                throw new InvalidFormatException(jsonParser, "JSON Schema for the custom app should have $id property",
                        valueNode.toPrettyString(), Map.class);
            }
            URI schemaId = URI.create(valueNode.get("$id").asText());
            Set<ValidationMessage> errors = CustomApplicationMetaSchemaHolder.schema.validate(valueNode);
            if (!errors.isEmpty()) {
                String message = "Failed to validate custom application schema " + schemaId + errors.stream()
                        .map(ValidationMessage::getMessage).collect(Collectors.joining(", "));
                throw new InvalidFormatException(jsonParser, message, valueNode.toPrettyString(), Map.class);
            }
            result = Stream.concat(result.entrySet().stream(), Stream.of(Map.entry(schemaId, valueNode.toString())))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return result;
    }

    private static class CustomApplicationMetaSchemaHolder {
        private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V7, builder ->
                        builder.schemaMappers(schemaMappers -> schemaMappers
                                .mapPrefix("https://dial.epam.com/custom_application_schemas",
                                        "classpath:custom-application-schemas")));

        public static JsonSchema schema = schemaFactory
                .getSchema(URI.create("https://dial.epam.com/custom_application_schemas/schema#"));
    }
}