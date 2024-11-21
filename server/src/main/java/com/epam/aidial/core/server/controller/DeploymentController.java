package com.epam.aidial.core.server.controller;


import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.DeploymentData;
import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.CustomApplicationUtils;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class DeploymentController {

    private final ProxyContext context;

    public Future<?> getDeployment(String deploymentId) {
        Config config = context.getConfig();
        Model model = config.getModels().get(deploymentId);

        if (model == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!DeploymentController.hasAccess(context, model)) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        DeploymentData data = createDeployment(model);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getDeployments() {
        Config config = context.getConfig();
        List<DeploymentData> deployments = new ArrayList<>();

        for (Model model : config.getModels().values()) {
            if (hasAccess(context, model)) {
                DeploymentData deployment = createDeployment(model);
                deployments.add(deployment);
            }
        }

        ListData<DeploymentData> list = new ListData<>();
        list.setData(deployments);

        return context.respond(HttpStatus.OK, list);
    }

    public static Future<Deployment> selectDeployment(ProxyContext context, String id, boolean filterCustomProperties, boolean modifyEndpoint) {
        Deployment deployment = context.getConfig().selectDeployment(id);
        Proxy proxy = context.getProxy();
        if (deployment != null) {
            if (!DeploymentController.hasAccess(context, deployment)) {
                return Future.failedFuture(new PermissionDeniedException("Forbidden deployment: " + id));
            } else {
                try {
                    if (deployment instanceof Application application) {
                        if (!modifyEndpoint && !filterCustomProperties) {
                            return Future.succeededFuture(deployment);
                        }
                        return proxy.getVertx().executeBlocking(() -> {
                            Application modifiedApp = application;
                            if (filterCustomProperties) {
                                modifiedApp = CustomApplicationUtils.filterCustomClientProperties(context.getConfig(), application);
                            }
                            if (modifyEndpoint) {
                                modifiedApp = CustomApplicationUtils.modifyEndpointForCustomApplication(context.getConfig(), modifiedApp);
                            }
                            return modifiedApp;
                        });
                    }
                    return Future.succeededFuture(deployment);
                } catch (Throwable e) {
                    return Future.failedFuture(e);
                }
            }
        }


        return proxy.getVertx().executeBlocking(() -> {
            String url;
            ResourceDescriptor resource;

            try {
                url = UrlUtil.encodePath(id);
                resource = ResourceDescriptorFactory.fromAnyUrl(url, proxy.getEncryptionService());
            } catch (Throwable ignore) {
                throw new ResourceNotFoundException("Unknown application: " + id);
            }

            if (resource.isFolder() || resource.getType() != ResourceTypes.APPLICATION) {
                throw new ResourceNotFoundException("Invalid application url: " + url);
            }

            if (!proxy.getAccessService().hasReadAccess(resource, context)) {
                throw new PermissionDeniedException();
            }

            Application app = proxy.getApplicationService().getApplication(resource).getValue();

            if (app.getCustomAppSchemaId() != null) {
                if (filterCustomProperties) {
                    app = CustomApplicationUtils.filterCustomClientPropertiesWhenNoWriteAccess(context, resource, app);
                }
                if (modifyEndpoint) {
                    app = CustomApplicationUtils.modifyEndpointForCustomApplication(context.getConfig(), app);
                }
            }

            return app;
        }, false);
    }

    public static boolean hasAccess(ProxyContext context, Deployment deployment) {
        Set<String> expectedUserRoles = deployment.getUserRoles();
        List<String> actualUserRoles = context.getUserRoles();

        if (expectedUserRoles == null) {
            return true;
        }

        return !expectedUserRoles.isEmpty()
                && actualUserRoles.stream().anyMatch(expectedUserRoles::contains);
    }

    private static DeploymentData createDeployment(Model model) {
        DeploymentData deployment = new DeploymentData();
        deployment.setId(model.getName());
        deployment.setModel(model.getName());
        return deployment;
    }

    static FeaturesData createFeatures(Features features) {
        FeaturesData data = new FeaturesData();

        if (features == null) {
            return data;
        }

        data.setRate(features.getRateEndpoint() != null);
        data.setTokenize(features.getTokenizeEndpoint() != null);
        data.setTruncatePrompt(features.getTruncatePromptEndpoint() != null);
        data.setConfiguration(features.getConfigurationEndpoint() != null);

        if (features.getSystemPromptSupported() != null) {
            data.setSystemPrompt(features.getSystemPromptSupported());
        }

        if (features.getToolsSupported() != null) {
            data.setTools(features.getToolsSupported());
        }

        if (features.getSeedSupported() != null) {
            data.setSeed(features.getSeedSupported());
        }

        if (features.getUrlAttachmentsSupported() != null) {
            data.setUrlAttachments(features.getUrlAttachmentsSupported());
        }

        if (features.getFolderAttachmentsSupported() != null) {
            data.setFolderAttachments(features.getFolderAttachmentsSupported());
        }

        if (features.getAllowResume() != null) {
            data.setAllowResume(features.getAllowResume());
        }

        if (features.getAccessibleByPerRequestKey() != null) {
            data.setAccessibleByPerRequestKey(features.getAccessibleByPerRequestKey());
        }

        if (features.getContentPartsSupported() != null) {
            data.setContentPartsSupported(features.getContentPartsSupported());
        }

        return data;
    }
}