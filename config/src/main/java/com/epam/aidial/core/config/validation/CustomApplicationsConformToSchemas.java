package com.epam.aidial.core.config.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ReportAsSingleViolation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

@Documented
@Constraint(validatedBy = { CustomApplicationsConformToSchemasValidator.class})
@Target({ TYPE })
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
public @interface CustomApplicationsConformToSchemas {
    String message() default "Custom applications should comply with their schemas";
    Class<?>[] groups() default {};
    Class<? extends jakarta.validation.Payload>[] payload() default {};
}
