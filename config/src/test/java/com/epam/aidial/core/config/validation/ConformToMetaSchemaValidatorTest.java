package com.epam.aidial.core.config.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

class ConformToMetaSchemaValidatorTest {

    private ConformToMetaSchemaValidator validator;

    @Mock
    private ConstraintValidatorContext context;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeBuilderCustomizableContext leafNodeBuilderCustomizableContext;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeContextBuilder leafNodeContextBuilder;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeBuilderDefinedContext leafNodeBuilderDefinedContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(context).disableDefaultConstraintViolation();
        when(context.buildConstraintViolationWithTemplate(any())).thenReturn(constraintViolationBuilder);
        when(constraintViolationBuilder.addBeanNode()).thenReturn(leafNodeBuilderCustomizableContext);
        when(leafNodeBuilderCustomizableContext.inContainer(Map.class, 1)).thenReturn(leafNodeBuilderCustomizableContext);
        when(leafNodeBuilderCustomizableContext.inIterable()).thenReturn(leafNodeContextBuilder);
        when(leafNodeContextBuilder.atKey(any())).thenReturn(leafNodeBuilderDefinedContext);
        when(leafNodeBuilderDefinedContext.addConstraintViolation()).thenReturn(context);
        validator = new ConformToMetaSchemaValidator();
    }

    @Test
    void isValidReturnsTrueWhenMapIsNull() {
        assertTrue(validator.isValid(null, context));
    }

    @Test
    void isValidReturnsTrueWhenMapIsEmpty() {
        assertTrue(validator.isValid(Collections.emptyMap(), context));
    }

    @Test
    void isValidReturnsFalseWhenSchemaValidationFails() {
        Map<String, String> invalidMap = new HashMap<>();
        invalidMap.put("invalidKey", "{\"invalid\": \"json\"}");
        assertFalse(validator.isValid(invalidMap, context));
    }

    @Test
    void isValidReturnsTrueWhenSchemaValidationPasses() {
        Map<String, String> validMap = new HashMap<>();
        String validSchema = "{"
                + "\"$schema\": \"https://dial.epam.com/application_type_schemas/schema#\","
                + "\"$id\": \"https://mydial.epam.com/custom_application_schemas/specific_application_type\","
                + "\"dial:applicationTypeEditorUrl\": \"https://mydial.epam.com/specific_application_type_editor\","
                + "\"dial:applicationTypeDisplayName\": \"Specific Application Type\","
                + "\"dial:applicationTypeCompletionEndpoint\": \"http://specific_application_service/opeani/v1/completion\","
                + "\"properties\": {"
                + "  \"file\": {"
                + "    \"type\": \"string\","
                + "    \"format\": \"dial-file-encoded\","
                + "    \"dial:meta\": {"
                + "      \"dial:propertyKind\": \"client\","
                + "      \"dial:propertyOrder\": 1"
                + "    }"
                + "  }"
                + "},"
                + "\"required\": [\"file\"]"
                + "}";
        validMap.put("validKey", validSchema);
        assertTrue(validator.isValid(validMap, context));
    }
}