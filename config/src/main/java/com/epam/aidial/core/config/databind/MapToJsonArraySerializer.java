package com.epam.aidial.core.config.databind;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

public class MapToJsonArraySerializer extends JsonSerializer<Map<URI, String>> {

    @Override
    public void serialize(Map<URI, String> uriStringMap, JsonGenerator jsonGenerator,
                          SerializerProvider serializerProvider) throws IOException {
        jsonGenerator.writeStartArray();
        for (Map.Entry<URI, String> entry : uriStringMap.entrySet()) {
            jsonGenerator.writeRaw(entry.getValue());
            jsonGenerator.writeRaw(",");
        }
        jsonGenerator.writeEndArray();
    }
}
