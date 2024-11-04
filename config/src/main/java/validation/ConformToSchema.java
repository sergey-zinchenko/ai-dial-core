package validation;

import com.networknt.schema.SpecVersion;
import jakarta.validation.Constraint;
import jakarta.validation.ReportAsSingleViolation;

import java.lang.annotation.*;
import java.util.Map;
import java.util.function.Function;

import static java.lang.annotation.ElementType.FIELD;

@Documented
@Constraint(validatedBy = { ConformToSchemaValidator.class})
@Target({ FIELD })
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
public @interface ConformToSchema {
    String message() default "JSON does not conform to schema";
    Class<? extends Function<Object, Map<String, String>>> schemaSource()
            default DefaultSchemaSourceFunction.class;
    Class<? extends Function<Object, String>> schemaId()
            default DefaultSchemaIdFunction.class;
    SpecVersion.VersionFlag version() default SpecVersion.VersionFlag.V7;
    class DefaultSchemaSourceFunction implements Function<Object, Map<String, String>> {
        @Override
        public Map<String, String> apply(Object o) {
            return Map.of();
        }
    }

    class DefaultSchemaIdFunction implements Function<Object, String> {
        @Override
        public String apply(Object o) {
            return "";
        }
    }
}


