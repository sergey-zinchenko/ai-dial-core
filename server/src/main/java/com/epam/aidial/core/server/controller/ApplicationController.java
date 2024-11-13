package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApplicationData;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ResourceLink;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.CustomAppValidationException;
import com.epam.aidial.core.server.util.CustomApplicationUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ApplicationController {

    private final ProxyContext context;
    private final Vertx vertx;
    private final EncryptionService encryptionService;
    private final AccessService accessService;
    private final ApplicationService applicationService;

    public ApplicationController(ProxyContext context) {
        this.context = context;
        this.vertx = context.getProxy().getVertx();
        this.encryptionService = context.getProxy().getEncryptionService();
        this.accessService = context.getProxy().getAccessService();
        this.applicationService = context.getProxy().getApplicationService();
    }

    public Future<?> getApplication(String applicationId) {
        DeploymentController.selectDeployment(context, applicationId)
                .map(deployment -> {
                    if (deployment instanceof Application application) {
                        try {
                            application =
                                    CustomApplicationUtils.filterCustomClientProperties(context.getConfig(), application);
                        } catch (CustomAppValidationException e) {
                            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                        }
                        return application;
                    }
                    throw new ResourceNotFoundException("Application is not found: " + applicationId);
                })
                .map(ApplicationUtil::mapApplication)
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    public Future<?> getApplications() {
        Config config = context.getConfig();
        List<ApplicationData> list = new ArrayList<>();

        for (Application application : config.getApplications().values()) {
            if (DeploymentController.hasAccess(context, application)) {
                application = CustomApplicationUtils.filterCustomClientProperties(config, application);
                ApplicationData data = ApplicationUtil.mapApplication(application);
                list.add(data);
            }
        }

        Future<List<ApplicationData>> future = Future.succeededFuture(list);

        if (applicationService.isIncludeCustomApps()) {
            future = vertx.executeBlocking(() -> applicationService.getAllApplications(context), false)
                    .map(apps -> {
                        apps.forEach(app -> list.add(ApplicationUtil.mapApplication(app)));
                        return list;
                    });
        }

        future.onSuccess(apps -> context.respond(HttpStatus.OK, new ListData<>(apps)))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    public Future<?> startApplication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodeUrl(url);
                    checkAccess(resource);
                    return vertx.executeBlocking(() -> applicationService.startApplication(context, resource), false);
                })
                .onSuccess(application -> context.respond(HttpStatus.OK, application))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    public Future<?> stopApplication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodeUrl(url);
                    checkAccess(resource);
                    return vertx.executeBlocking(() -> applicationService.stopApplication(resource), false);
                })
                .onSuccess(application -> context.respond(HttpStatus.OK, application))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    public Future<?> getApplicationLogs() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodeUrl(url);
                    checkAccess(resource);
                    return vertx.executeBlocking(() -> applicationService.getApplicationLogs(resource, context), false);
                })
                .onSuccess(logs -> context.respond(HttpStatus.OK, logs))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    private ResourceDescriptor decodeUrl(String url) {
        ResourceDescriptor resource;
        try {
            resource = ResourceDescriptorFactory.fromAnyUrl(url, encryptionService);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid application: " + url, e);
        }

        if (resource.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Invalid application: " + url);
        }

        return resource;
    }

    private void checkAccess(ResourceDescriptor resource) {
        boolean hasAccess = accessService.hasAdminAccess(context);

        if (!hasAccess && resource.isPrivate()) {
            String bucket = BucketBuilder.buildInitiatorBucket(context);
            hasAccess = resource.getBucketLocation().equals(bucket);
        }

        if (!hasAccess) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden operation for application: " + resource.getUrl());
        }
    }

    private void respondError(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to handle application request", error);
            context.respond(error, "Internal error");
        }
    }
}