package com.epam.aidial.core.metaschemas;

import lombok.experimental.UtilityClass;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

@UtilityClass
public class MetaSchemaHolder {

    public static final String CUSTOM_APPLICATION_META_SCHEMA_ID = "https://dial.epam.com/custom_application_schemas/schema#";

    public static String getCustomApplicationMetaSchema() {
        try (InputStream inputStream = MetaSchemaHolder.class.getClassLoader()
                .getResourceAsStream("custom-application-schemas/schema")) {
            assert inputStream != null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load custom application meta schema", e);
        }
    }
}
