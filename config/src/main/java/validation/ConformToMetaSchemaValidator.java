package validation;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.util.Map;

public class ConformToMetaSchemaValidator implements ConstraintValidator<ConformToMetaSchema, Map<String, String>> {

    private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7, builder ->
            builder.schemaMappers(schemaMappers -> schemaMappers
                    .mapPrefix("https://dial.epam.com/custom_application_schemas", "classpath:custom-application-schemas")));

    private static final JsonSchema schema = schemaFactory.getSchema(URI.create("https://dial.epam.com/custom_application_schemas/schema#"));

    @Override
    public boolean isValid(Map<String, String> stringStringMap, ConstraintValidatorContext context) {
        if (stringStringMap == null) {
            return true;
        }
        for (Map.Entry<String, String> entry : stringStringMap.entrySet()) {
            if (!schema.validate(entry.getValue(), InputFormat.JSON).isEmpty()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addBeanNode()
                        .inContainer(Map.class, 1)
                        .inIterable().atKey(entry.getKey())
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}