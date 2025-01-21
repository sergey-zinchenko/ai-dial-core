package com.epam.aidial.core.server.validation;

import lombok.Getter;


/**
 * Exception thrown when there is an issue with a resource associated with an application type.
 * This exception is typically used when a resource listed as dependent to the application
 * is not found, inaccessible, or there is a failure in obtaining the resource descriptor.
 */
@Getter
public class ApplicationTypeResourceException extends RuntimeException {

    private final String resourceUri;

    public ApplicationTypeResourceException(String message, String resourceUri, Throwable cause) {
        super(message, cause);
        this.resourceUri = resourceUri;
    }

    public ApplicationTypeResourceException(String message, String resourceUri) {
        super(message);
        this.resourceUri = resourceUri;
    }

}