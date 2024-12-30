package com.epam.aidial.core.config.databind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonArrayToSchemaMapDeserializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesValidJsonArray() throws IOException {
        String jsonArray = "[{\"$id\": \"schema1\", \"type\": \"object\"}, {\"$id\": \"schema2\", \"type\": \"object\"}]";
        JsonParser parser = MAPPER.getFactory().createParser(jsonArray);
        JsonArrayToSchemaMapDeserializer deserializer = new JsonArrayToSchemaMapDeserializer();
        Map<String, String> result = deserializer.deserialize(parser, null);
        assertEquals(2, result.size());
        assertTrue(result.containsKey("schema1"));
        assertTrue(result.containsKey("schema2"));
    }

    @Test
    void throwsExceptionForNonArrayInput() throws IOException {
        String jsonObject = "{\"$id\": \"schema1\", \"type\": \"object\"}";
        JsonParser parser = MAPPER.getFactory().createParser(jsonObject);
        JsonArrayToSchemaMapDeserializer deserializer = new JsonArrayToSchemaMapDeserializer();
        assertThrows(InvalidFormatException.class, () -> deserializer.deserialize(parser, null));
    }

    @Test
    void throwsExceptionForArrayWithNullValue() throws IOException {
        String jsonArray = "[{\"$id\": \"schema1\", \"type\": \"object\"}, null]";
        JsonParser parser = MAPPER.getFactory().createParser(jsonArray);
        JsonArrayToSchemaMapDeserializer deserializer = new JsonArrayToSchemaMapDeserializer();
        assertThrows(MismatchedInputException.class, () -> deserializer.deserialize(parser, null));
    }

    @Test
    void throwsExceptionForNonObjectValuesInArray() throws IOException {
        String jsonArray = "[{\"$id\": \"schema1\", \"type\": \"object\"}, \"stringValue\"]";
        JsonParser parser = MAPPER.getFactory().createParser(jsonArray);
        JsonArrayToSchemaMapDeserializer deserializer = new JsonArrayToSchemaMapDeserializer();
        assertThrows(MismatchedInputException.class, () -> deserializer.deserialize(parser, null));
    }

    @Test
    void throwsExceptionForObjectWithoutId() throws IOException {
        String jsonArray = "[{\"type\": \"object\"}]";
        JsonParser parser = MAPPER.getFactory().createParser(jsonArray);
        JsonArrayToSchemaMapDeserializer deserializer = new JsonArrayToSchemaMapDeserializer();
        assertThrows(InvalidFormatException.class, () -> deserializer.deserialize(parser, null));
    }
}
