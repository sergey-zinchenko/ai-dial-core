package com.epam.aidial.core.config.databind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsonArrayToSchemaMapDeserializer extends JsonDeserializer<Map<String, String>> {

    @Override
    public Map<String, String> deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        TreeNode tree = jsonParser.readValueAsTree();
        if (!tree.isArray()) {
            throw InvalidFormatException.from(jsonParser, "Expected a JSON array of schemas", tree.toString(), Map.class);
        }
        Map<String, String> result = Map.of();
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
            String schemaId = valueNode.get("$id").asText();
            result = Stream.concat(result.entrySet().stream(), Stream.of(Map.entry(schemaId, valueNode.toString())))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return result;
    }
}