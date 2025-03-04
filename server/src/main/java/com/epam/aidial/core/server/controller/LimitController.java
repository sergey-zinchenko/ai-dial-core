package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LimitController {

    private final Proxy proxy;

    private final ProxyContext context;

    public LimitController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
    }

    public Future<?> getLimits(String deploymentId) {
        DeploymentController.selectDeployment(context, deploymentId, false, true)
                .compose(dep -> proxy.getRateLimiter().getLimitStats(dep, context))
                .onSuccess(limitStats -> {
                    if (limitStats == null) {
                        context.respond(HttpStatus.NOT_FOUND);
                    } else {
                        context.respond(HttpStatus.OK, limitStats);
                    }
                }).onFailure(error -> handleRequestError(deploymentId, error));

        return Future.succeededFuture();
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.error("LimitController. Forbidden deployment {}. Project: {}. User sub: {}", deploymentId, context.getProject(), context.getUserSub());
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.error("LimitController. Deployment not found {}", deploymentId, error);
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("LimitController. Failed to get limit stats", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to get limit stats for deployment=%s".formatted(deploymentId));
        }
    }

}
