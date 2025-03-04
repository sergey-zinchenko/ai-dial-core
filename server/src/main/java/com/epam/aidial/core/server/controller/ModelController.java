package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.TokenLimits;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ModelData;
import com.epam.aidial.core.server.data.PricingData;
import com.epam.aidial.core.server.data.TokenLimitsData;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class ModelController {

    private final ProxyContext context;

    public Future<?> getModel(String modelId) {
        Config config = context.getConfig();
        Model model = config.getModels().get(modelId);

        if (model == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!model.hasAccess(context.getUserRoles())) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        ModelData data = createModel(model);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getModels() {
        Config config = context.getConfig();
        List<ModelData> models = new ArrayList<>();

        for (Model model : config.getModels().values()) {
            if (model.hasAccess(context.getUserRoles())) {
                ModelData data = createModel(model);
                models.add(data);
            }
        }

        ListData<ModelData> list = new ListData<>();
        list.setData(models);

        return context.respond(HttpStatus.OK, list);
    }

    static ModelData createModel(Model model) {
        ModelData data = new ModelData();
        data.setId(model.getName());
        data.setModel(model.getName());
        data.setDisplayName(model.getDisplayName());
        data.setDisplayVersion(model.getDisplayVersion());
        data.setIconUrl(model.getIconUrl());
        data.setDescription(model.getDescription());
        data.setFeatures(DeploymentController.createFeatures(model.getFeatures()));
        data.setInputAttachmentTypes(model.getInputAttachmentTypes());
        data.setMaxInputAttachments(model.getMaxInputAttachments());
        data.setReference(model.getName());
        data.setDescriptionKeywords(model.getDescriptionKeywords());
        data.setMaxRetryAttempts(model.getMaxRetryAttempts());

        if (model.getType() == ModelType.EMBEDDING) {
            data.getCapabilities().setEmbeddings(true);
        } else if (model.getType() == ModelType.COMPLETION) {
            data.getCapabilities().setCompletion(true);
        } else if (model.getType() == ModelType.CHAT) {
            data.getCapabilities().setChatCompletion(true);
        }

        data.setTokenizerModel(model.getTokenizerModel());
        data.setLimits(createLimits(model.getLimits()));
        data.setPricing(createPricing(model.getPricing()));
        data.setDefaults(model.getDefaults());
        if (model.getAuthor() != null) {
            data.setOwner(model.getAuthor());
        }
        if (model.getCreatedAt() != null) {
            data.setCreatedAt(model.getCreatedAt());
        }
        if (model.getUpdatedAt() != null) {
            data.setUpdatedAt(model.getUpdatedAt());
        }
        return data;
    }

    private static TokenLimitsData createLimits(TokenLimits limits) {
        if (limits == null) {
            return null;
        }
        TokenLimitsData data = new TokenLimitsData();
        data.setMaxPromptTokens(limits.getMaxPromptTokens());
        data.setMaxCompletionTokens(limits.getMaxCompletionTokens());
        data.setMaxTotalTokens(limits.getMaxTotalTokens());
        return data;
    }

    private static PricingData createPricing(Pricing pricing) {
        if (pricing == null) {
            return null;
        }
        PricingData data = new PricingData();
        data.setUnit(pricing.getUnit());
        data.setPrompt(pricing.getPrompt());
        data.setCompletion(pricing.getCompletion());
        return data;
    }
}