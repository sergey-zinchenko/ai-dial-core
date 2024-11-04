package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.util.Map;

public class CustomApplicationsConformToSchemasValidator implements ConstraintValidator<CustomApplicationsConformToSchemas, Config> {

    @Override
    public boolean isValid(Config value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7, builder ->
                builder.schemaLoaders(loaders -> loaders.schemas(value.getCustomApplicationSchemas()))
                        .schemaMappers(schemaMappers -> schemaMappers
                                .mapPrefix("https://dial.epam.com/custom_application_schemas",
                                        "classpath:custom-application-schemas"))
        );

        ObjectMapper mapper = new ObjectMapper();
        for (Map.Entry<String, Application> entry : value.getApplications().entrySet()) {
            Application application = entry.getValue();
            URI schemaId = application.getCustomAppSchemaId();
            if (schemaId == null) {
                continue;
            }

            JsonSchema schema = schemaFactory.getSchema(schemaId);
            JsonNode applicationNode = mapper.valueToTree(application);
            if (!schema.validate(applicationNode).isEmpty()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("applications")
                        .addContainerElementNode(entry.getKey(), Map.class, 0)
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}