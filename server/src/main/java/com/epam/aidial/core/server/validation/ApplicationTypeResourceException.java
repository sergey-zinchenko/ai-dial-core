package com.epam.aidial.core.server.validation;

import lombok.Getter;

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