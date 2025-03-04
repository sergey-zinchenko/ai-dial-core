package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.ApplicationData;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@UtilityClass
@Slf4j
public class ApplicationUtil {

    public ApplicationData mapApplication(Application application) {
        ApplicationData data = new ApplicationData();
        data.setId(application.getName());
        data.setApplication(application.getName());
        data.setDisplayName(application.getDisplayName());
        data.setDisplayVersion(application.getDisplayVersion());
        data.setIconUrl(application.getIconUrl());
        data.setDescription(application.getDescription());
        data.setFeatures(DeploymentController.createFeatures(application.getFeatures()));
        data.setInputAttachmentTypes(application.getInputAttachmentTypes());
        data.setMaxInputAttachments(application.getMaxInputAttachments());
        data.setDefaults(application.getDefaults());
        data.setDescriptionKeywords(application.getDescriptionKeywords());

        data.setApplicationTypeSchemaId(application.getApplicationTypeSchemaId());
        data.setApplicationProperties(application.getApplicationProperties());
        String reference = application.getReference();
        data.setReference(reference == null ? application.getName() : reference);
        data.setFunction(application.getFunction());
        data.setMaxRetryAttempts(application.getMaxRetryAttempts());

        if (application.getAuthor() != null) {
            data.setOwner(application.getAuthor());
        }
        if (application.getCreatedAt() != null) {
            data.setCreatedAt(application.getCreatedAt());
        }
        if (application.getUpdatedAt() != null) {
            data.setUpdatedAt(application.getUpdatedAt());
        }

        return data;
    }

    public String generateReference() {
        return UUID.randomUUID().toString();
    }
}
