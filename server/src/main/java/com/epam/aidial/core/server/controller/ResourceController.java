package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Conversation;
import com.epam.aidial.core.server.data.Prompt;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaProcessingException;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.storage.http.HttpStatus.FORBIDDEN;
import static com.epam.aidial.core.storage.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Slf4j
@SuppressWarnings("checkstyle:Indentation")
public class ResourceController extends AccessControlBaseController {

    private final Vertx vertx;
    private final EncryptionService encryptionService;
    private final ResourceService resourceService;
    private final ApplicationService applicationService;
    private final boolean metadata;
    private final AccessService accessService;

    public ResourceController(Proxy proxy, ProxyContext context, boolean metadata) {
        // PUT and DELETE require write access, GET - read
        super(proxy, context, !HttpMethod.GET.equals(context.getRequest().method()));
        this.vertx = proxy.getVertx();
        this.encryptionService = proxy.getEncryptionService();
        this.applicationService = proxy.getApplicationService();
        this.accessService = proxy.getAccessService();
        this.resourceService = proxy.getResourceService();
        this.metadata = metadata;
    }

    @Override
    protected Future<?> handle(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        if (context.getRequest().method() == HttpMethod.GET) {
            return metadata ? getMetadata(descriptor) : getResource(descriptor, hasWriteAccess);
        }

        if (context.getRequest().method() == HttpMethod.PUT) {
            return putResource(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.DELETE) {
            return deleteResource(descriptor);
        }
        log.warn("Unsupported HTTP method for accessing resource {}", descriptor.getUrl());
        return context.respond(HttpStatus.BAD_REQUEST, "Unsupported HTTP method");
    }

    private String getContentType() {
        String acceptType = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return acceptType != null && metadata && acceptType.contains(MetadataBase.MIME_TYPE)
                ? MetadataBase.MIME_TYPE
                : "application/json";
    }

    private Future<?> getMetadata(ResourceDescriptor descriptor) {
        String token;
        int limit;
        boolean recursive;

        try {
            token = context.getRequest().getParam("token");
            limit = Integer.parseInt(context.getRequest().getParam("limit", "100"));
            recursive = Boolean.parseBoolean(context.getRequest().getParam("recursive", "false"));
            if (limit < 0 || limit > 1000) {
                throw new IllegalArgumentException("Limit is out of allowed range");
            }
        } catch (Throwable error) {
            return context.respond(BAD_REQUEST, "Bad query parameters. Limit must be in [0, 1000] range. Recursive must be true/false");
        }

        vertx.executeBlocking(() -> resourceService.getMetadata(descriptor, token, limit, recursive), false)
                .onSuccess(result -> {
                    if (result == null) {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    } else {
                        accessService.filterForbidden(context, descriptor, result);
                        if (context.getBooleanRequestQueryParam("permissions")) {
                            accessService.populatePermissions(context, List.of(result));
                        }
                        context.respond(HttpStatus.OK, getContentType(), result);
                    }
                })
                .onFailure(error -> {
                    log.warn("Can't list resource: {}", descriptor.getUrl(), error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                });

        return Future.succeededFuture();
    }

    private Future<?> getResource(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        if (descriptor.isFolder()) {
            return context.respond(BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        EtagHeader etagHeader = ProxyUtil.etag(context.getRequest());
        Future<Pair<ResourceItemMetadata, String>> responseFuture = (descriptor.getType() == ResourceTypes.APPLICATION)
                ? getApplicationData(descriptor, hasWriteAccess, etagHeader) : getResourceData(descriptor, etagHeader);

        responseFuture.onSuccess(pair -> context.putHeader(HttpHeaders.ETAG, pair.getKey().getEtag())
                .exposeHeaders()
                .respond(HttpStatus.OK, pair.getValue()))
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private Future<Pair<ResourceItemMetadata, String>> getApplicationData(ResourceDescriptor descriptor, boolean hasWriteAccess, EtagHeader etagHeader) {
        return vertx.executeBlocking(() -> {
            Pair<ResourceItemMetadata, Application> result = applicationService.getApplication(descriptor, etagHeader);
            ResourceItemMetadata meta = result.getKey();

            Application application = result.getValue();
            String body = hasWriteAccess
                    ? ProxyUtil.convertToString(application)
                    : ProxyUtil.convertToString(ApplicationUtil.mapApplication(application));

            return Pair.of(meta, body);

        }, false);
    }

    private Future<Pair<ResourceItemMetadata, String>> getResourceData(ResourceDescriptor descriptor, EtagHeader etag) {
        return vertx.executeBlocking(() -> {
            Pair<ResourceItemMetadata, String> result = resourceService.getResourceWithMetadata(descriptor, etag);

            if (result == null) {
                throw new ResourceNotFoundException();
            }

            return result;
        }, false);
    }

    private void validateCustomApplication(Application application) {
        try {
            checkCreateCodeApp(application);
            Config config = context.getConfig();
            List<ResourceDescriptor> files = ApplicationTypeSchemaUtils.getFiles(config, application, encryptionService,
                    resourceService);
            files.stream().filter(resource -> !(accessService.hasReadAccess(resource, context)))
                    .findAny().ifPresent(file -> {
                        throw new HttpException(BAD_REQUEST, "No read access to file: " + file.getUrl());
                    });
        } catch (ValidationException | IllegalArgumentException | ApplicationTypeSchemaValidationException e) {
            throw new HttpException(BAD_REQUEST, "Custom application validation failed", e);
        } catch (ApplicationTypeResourceException e) {
            throw new HttpException(FORBIDDEN, "Failed to access application resource " + e.getResourceUri(), e);
        } catch (ApplicationTypeSchemaProcessingException e) {
            throw new HttpException(INTERNAL_SERVER_ERROR, "Custom application processing exception", e);
        }
    }

    private void checkCreateCodeApp(Application application) {
        if (application != null && application.getFunction() != null && !accessService.canCreateCodeApps(context)) {
            throw new PermissionDeniedException("User doesn't have sufficient permissions to create code app");
        }
    }

    private Future<?> putResource(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        if (!ResourceDescriptorFactory.isValidResourcePath(descriptor)) {
            return context.respond(BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }

        int contentLength = ProxyUtil.contentLength(context.getRequest(), 0);
        int contentLimit = resourceService.getMaxSize();

        if (contentLength > contentLimit) {
            String message = "Resource size: %s exceeds max limit: %s".formatted(contentLength, contentLimit);
            return context.respond(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
        }

        Future<Pair<EtagHeader, String>> requestFuture = context.getRequest().body().map(bytes -> {
            if (bytes.length() > contentLimit) {
                String message = "Resource size: %s exceeds max limit: %s".formatted(bytes.length(), contentLimit);
                throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
            }

            EtagHeader etag = ProxyUtil.etag(context.getRequest());
            String body = bytes.toString(StandardCharsets.UTF_8);

            return Pair.of(etag, body);
        });

        String author = context.getUserDisplayName();
        Future<ResourceItemMetadata> responseFuture;
        if (descriptor.getType() == ResourceTypes.APPLICATION) {
            responseFuture = requestFuture.compose(pair -> {
                EtagHeader etag = pair.getKey();
                Application application = ProxyUtil.convertToObject(pair.getValue(), Application.class);
                return vertx.executeBlocking(() -> {
                    validateCustomApplication(application);
                    return applicationService.putApplication(descriptor, etag, author, application).getKey();
                }, false);
            });
        } else {
            responseFuture = requestFuture.compose(pair -> {
                EtagHeader etag = pair.getKey();
                String body = pair.getValue();
                validateRequestBody(descriptor, body);
                return vertx.executeBlocking(() -> resourceService.putResource(descriptor, body, etag, author), false);
            });
        }

        responseFuture.onSuccess((metadata) -> context.putHeader(HttpHeaders.ETAG, metadata.getEtag())
                .exposeHeaders()
                .respond(HttpStatus.OK, metadata))
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private Future<?> deleteResource(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return context.respond(BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        EtagHeader etag = ProxyUtil.etag(context.getRequest());

        vertx.executeBlocking(() -> proxy.getResourceOperationService().deleteResource(descriptor, etag), false)
                .onSuccess(deleted -> {
                    if (deleted) {
                        context.respond(HttpStatus.OK);
                    } else {
                        context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
                    }
                })
                .onFailure(error -> handleError(descriptor, error));

        return Future.succeededFuture();
    }

    private void handleError(ResourceDescriptor descriptor, Throwable error) {
        if (error instanceof HttpException exception) {
            context.respond(exception);
        } else if (error instanceof IllegalArgumentException) {
            context.respond(BAD_REQUEST, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            context.respond(HttpStatus.NOT_FOUND, "Not found: " + descriptor.getUrl());
        } else if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else {
            log.warn("Can't handle resource request: {}", descriptor.getUrl(), error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static void validateRequestBody(ResourceDescriptor descriptor, String body) {
        switch ((ResourceTypes) descriptor.getType()) {
            case PROMPT -> ProxyUtil.convertToObject(body, Prompt.class);
            case CONVERSATION -> ProxyUtil.convertToObject(body, Conversation.class);
            default -> throw new IllegalArgumentException("Unsupported resource type " + descriptor.getType());
        }
    }
}