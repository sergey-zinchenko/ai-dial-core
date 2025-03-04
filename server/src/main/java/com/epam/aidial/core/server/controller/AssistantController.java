package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AssistantData;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

import static com.epam.aidial.core.config.Config.ASSISTANT;

@RequiredArgsConstructor
public class AssistantController {

    private final ProxyContext context;

    public Future<?> getAssistant(String assistantId) {
        Config config = context.getConfig();
        Assistant assistant = config.getAssistant().getAssistants().get(assistantId);

        if (assistant == null || ASSISTANT.equals(assistant.getName())) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!assistant.hasAccess(context.getUserRoles())) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        AssistantData data = createAssistant(assistant);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getAssistants() {
        Config config = context.getConfig();
        List<AssistantData> assistants = new ArrayList<>();

        for (Assistant assistant : config.getAssistant().getAssistants().values()) {
            if (!ASSISTANT.equals(assistant.getName()) && assistant.hasAccess(context.getUserRoles())) {
                AssistantData data = createAssistant(assistant);
                assistants.add(data);
            }
        }

        ListData<AssistantData> list = new ListData<>();
        list.setData(assistants);

        return context.respond(HttpStatus.OK, list);
    }

    private static AssistantData createAssistant(Assistant assistant) {
        AssistantData data = new AssistantData();
        data.setId(assistant.getName());
        data.setAssistant(assistant.getName());
        data.setDisplayName(assistant.getDisplayName());
        data.setDisplayVersion(assistant.getDisplayVersion());
        data.setIconUrl(assistant.getIconUrl());
        data.setDescription(assistant.getDescription());
        data.setAddons(assistant.getAddons());
        data.setFeatures(DeploymentController.createFeatures(assistant.getFeatures()));
        data.setInputAttachmentTypes(assistant.getInputAttachmentTypes());
        data.setMaxInputAttachments(assistant.getMaxInputAttachments());
        data.setDefaults(assistant.getDefaults());
        data.setReference(assistant.getName());
        data.setDescriptionKeywords(assistant.getDescriptionKeywords());
        if (assistant.getAuthor() != null) {
            data.setOwner(assistant.getAuthor());
        }
        if (assistant.getCreatedAt() != null) {
            data.setCreatedAt(assistant.getCreatedAt());
        }
        if (assistant.getUpdatedAt() != null) {
            data.setUpdatedAt(assistant.getUpdatedAt());
        }
        return data;
    }
}