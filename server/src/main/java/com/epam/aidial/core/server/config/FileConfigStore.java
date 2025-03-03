package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Addon;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Assistants;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.validation.ValidationModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

import static com.epam.aidial.core.config.Config.ASSISTANT;


@Slf4j
public final class FileConfigStore implements ConfigStore {

    private final JsonMapper jsonMapper;
    private final String[] paths;
    private volatile Config config;
    private final ApiKeyStore apiKeyStore;

    public FileConfigStore(Vertx vertx, JsonObject settings, ApiKeyStore apiKeyStore) {
        this.jsonMapper = buildJsonMapper(settings);
        this.apiKeyStore = apiKeyStore;
        this.paths = settings.getJsonArray("files")
                .stream().map(path -> (String) path).toArray(String[]::new);

        long period = settings.getLong("reload");
        load(true);
        vertx.setPeriodic(period, period, event -> load(false));
    }

    @Override
    public Config get() {
        return config;
    }

    @Override
    public Config reload() {
        return load(true);
    }

    @SneakyThrows
    private Config load(boolean fail) {
        try {
            log.debug("Config loading is started");
            Config config = loadConfig();

            for (Map.Entry<String, Route> entry : config.getRoutes().entrySet()) {
                String name = entry.getKey();
                Route route = entry.getValue();
                route.setName(name);
                log.debug("Loading {}", route);
            }

            for (Map.Entry<String, Model> entry : config.getModels().entrySet()) {
                String name = entry.getKey();
                Model model = entry.getValue();
                model.setName(name);
                log.debug("Loading {}", model);
            }

            for (Map.Entry<String, Addon> entry : config.getAddons().entrySet()) {
                String name = entry.getKey();
                Addon addon = entry.getValue();
                addon.setName(name);
                log.debug("Loading {}", addon);
            }

            Assistants assistants = config.getAssistant();
            for (Map.Entry<String, Assistant> entry : assistants.getAssistants().entrySet()) {
                String name = entry.getKey();
                Assistant assistant = entry.getValue();
                assistant.setName(name);

                if (assistant.getEndpoint() == null) {
                    assistant.setEndpoint(assistants.getEndpoint());
                }

                setMissingFeatures(assistant, assistants.getFeatures());
                log.debug("Loading {}", assistant);
            }
            // base assistant
            if (assistants.getEndpoint() != null) {
                Assistant baseAssistant = new Assistant();
                baseAssistant.setName(ASSISTANT);
                baseAssistant.setEndpoint(assistants.getEndpoint());
                baseAssistant.setFeatures(assistants.getFeatures());
                assistants.getAssistants().put(ASSISTANT, baseAssistant);
            }

            for (Map.Entry<String, Application> entry : config.getApplications().entrySet()) {
                String name = entry.getKey();
                Application application = entry.getValue();
                application.setName(name);
                log.debug("Loading {}", application);
            }

            apiKeyStore.addProjectKeys(config.getKeys());

            for (Map.Entry<String, Role> entry : config.getRoles().entrySet()) {
                String name = entry.getKey();
                Role role = entry.getValue();
                role.setName(name);
                log.debug("Start loading role `{}`", role.getName());
                for (Map.Entry<String, Limit> limitEntry : role.getLimits().entrySet()) {
                    log.debug("Loading {} for deployment `{}`", limitEntry.getValue(), limitEntry.getKey());
                }
                log.debug("End loading role `{}`", role.getName());
            }

            for (Map.Entry<String, Interceptor> entry : config.getInterceptors().entrySet()) {
                String name = entry.getKey();
                Interceptor interceptor = entry.getValue();
                interceptor.setName(name);
                log.debug("Interceptor {}", interceptor);
            }

            this.config = config;
            log.debug("Config loading is completed");
            return config;
        } catch (Throwable e) {
            if (fail) {
                throw e;
            }

            log.warn("Failed to reload config: {}", e.getMessage());
        }
        return null;
    }

    private Config loadConfig() throws Exception {
        JsonNode tree = jsonMapper.createObjectNode();

        for (String path : paths) {
            try (InputStream stream = openStream(path)) {
                tree = jsonMapper.readerForUpdating(tree).readTree(stream);
            }
        }

        return jsonMapper.convertValue(tree, Config.class);
    }

    @SneakyThrows
    private static InputStream openStream(String path) {
        try {
            return new BufferedInputStream(new FileInputStream(path));
        } catch (FileNotFoundException e) {
            InputStream stream = ConfigStore.class.getClassLoader().getResourceAsStream(path);
            if (stream == null) {
                throw new FileNotFoundException("File not found: " + path);
            }
            return stream;
        }
    }

    private static void setMissingFeatures(Deployment model, Features features) {
        if (features == null) {
            return;
        }

        Features modelFeatures = model.getFeatures();
        if (modelFeatures == null) {
            model.setFeatures(features);
            return;
        }

        if (modelFeatures.getRateEndpoint() == null) {
            modelFeatures.setRateEndpoint(features.getRateEndpoint());
        }
        if (modelFeatures.getTokenizeEndpoint() == null) {
            modelFeatures.setTokenizeEndpoint(features.getTokenizeEndpoint());
        }
        if (modelFeatures.getTruncatePromptEndpoint() == null) {
            modelFeatures.setTruncatePromptEndpoint(features.getTruncatePromptEndpoint());
        }
        if (modelFeatures.getSystemPromptSupported() == null) {
            modelFeatures.setSystemPromptSupported(features.getSystemPromptSupported());
        }
        if (modelFeatures.getToolsSupported() == null) {
            modelFeatures.setToolsSupported(features.getToolsSupported());
        }
        if (modelFeatures.getSeedSupported() == null) {
            modelFeatures.setSeedSupported(features.getSeedSupported());
        }
        if (modelFeatures.getUrlAttachmentsSupported() == null) {
            modelFeatures.setUrlAttachmentsSupported(features.getUrlAttachmentsSupported());
        }
        if (modelFeatures.getFolderAttachmentsSupported() == null) {
            modelFeatures.setFolderAttachmentsSupported(features.getFolderAttachmentsSupported());
        }
        if (modelFeatures.getAllowResume() == null) {
            modelFeatures.setAllowResume(features.getAllowResume());
        }
        if (modelFeatures.getAccessibleByPerRequestKey() == null) {
            modelFeatures.setAccessibleByPerRequestKey(features.getAccessibleByPerRequestKey());
        }
        if (modelFeatures.getContentPartsSupported() == null) {
            modelFeatures.setContentPartsSupported(features.getContentPartsSupported());
        }
        if (modelFeatures.getTemperatureSupported() == null) {
            modelFeatures.setTemperatureSupported(features.getTemperatureSupported());
        }
        if (modelFeatures.getAddonsSupported() == null) {
            modelFeatures.setAddonsSupported(features.getAddonsSupported());
        }
    }

    private JsonMapper buildJsonMapper(JsonObject settings) {
        JsonMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .addModule(new ValidationModule())
                .build();

        boolean overwriteArrays = settings
                .getJsonObject("jsonMergeStrategy", new JsonObject())
                .getBoolean("overwriteArrays", false);

        mapper.configOverride(ArrayNode.class)
                .setMergeable(!overwriteArrays);

        return mapper;
    }
}
