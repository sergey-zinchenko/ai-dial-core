package com.epam.aidial.core.server.util;

import com.networknt.schema.ValidationMessage;
import lombok.Getter;

import java.util.Set;

@Getter
public class CustomAppValidationException extends RuntimeException {
    private Set<ValidationMessage> validationMessages = Set.of();

    public CustomAppValidationException(String message, Set<ValidationMessage> validationMessages) {
        super(message);
        this.validationMessages = validationMessages;
    }

    public CustomAppValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public CustomAppValidationException(String message) {
        super(message);
    }
}
