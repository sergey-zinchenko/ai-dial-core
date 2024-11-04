package validation;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.SneakyThrows;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class ConformToSchemaValidator implements ConstraintValidator<ConformToSchema, Object> {

    private Class<? extends Function<Object, Map<String, String>>> schemaSourceClass;
    private Class<? extends Function<Object, String>> schemaIdClass;
    private SpecVersion.VersionFlag version;

    @Override
    public void initialize(ConformToSchema constraintAnnotation) {
        this.schemaSourceClass = constraintAnnotation.schemaSource();
        this.version = constraintAnnotation.version();
        this.schemaIdClass = constraintAnnotation.schemaId();
    }

    @SneakyThrows
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        Object rootBean = context.unwrap(jakarta.validation.ValidationContext.class).getRootBean();

        Map<String, String> schemas = FunctionUtils.applyFunction(schemaSourceClass, value);
        String schemaId = FunctionUtils.applyFunction(schemaIdClass, value);

        if (schemaId.isEmpty() && schemas.isEmpty()) {
            return false;
        }

        JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(version,
                builder -> builder.schemaLoaders(loaders -> loaders.schemas(schemas)));

        if (schemaId.isEmpty() && !schemas.isEmpty()) {
            schemaId = schemas.keySet().iterator().next();
        }

        JsonSchema schema = schemaFactory.getSchema(URI.create(schemaId));
        Set<ValidationMessage> validationMessages = schema.validate(value.toString(), InputFormat.JSON);

        if (!validationMessages.isEmpty()) {
            context.disableDefaultConstraintViolation();
            for (ValidationMessage message : validationMessages) {
                context.buildConstraintViolationWithTemplate(message.getMessage())
                        .addConstraintViolation();
            }
            return false;
        }

        return true;
    }

    private static class FunctionUtils {

        public static <T, R> R applyFunction(Class<? extends Function<T, R>> functionClass, T value) throws Exception {
            Function<T, R> function = functionClass.getDeclaredConstructor().newInstance();
            return function.apply(value);
        }
    }
}