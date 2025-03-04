package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.ApplicationUtil;
import com.epam.aidial.core.server.data.ListSharedResourcesRequest;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.SharedResourcesResponse;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaUtils;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;


@Slf4j
public class ApplicationService {

    private static final String DEPLOYMENTS_NAME = "deployments";
    private static final int PAGE_SIZE = 1000;

    private final Vertx vertx;
    private final EncryptionService encryptionService;
    private final ResourceService resourceService;
    private final LockService lockService;
    private final Supplier<String> idGenerator;
    private final RScoredSortedSet<String> pendingApplications;
    private final ApplicationOperatorService controller;
    private final long checkDelay;
    private final int checkSize;
    @Getter
    private final boolean includeCustomApps;

    public ApplicationService(Vertx vertx,
                              RedissonClient redis,
                              EncryptionService encryptionService,
                              ResourceService resourceService,
                              LockService lockService,
                              ApplicationOperatorService operatorService,
                              Supplier<String> idGenerator,
                              JsonObject settings) {
        String pendingApplicationsKey = BlobStorageUtil.toStoragePath(lockService.getPrefix(), "pending-applications");

        this.vertx = vertx;
        this.encryptionService = encryptionService;
        this.resourceService = resourceService;
        this.lockService = lockService;
        this.idGenerator = idGenerator;
        this.pendingApplications = redis.getScoredSortedSet(pendingApplicationsKey, StringCodec.INSTANCE);
        this.controller = operatorService;
        this.checkDelay = settings.getLong("checkDelay", 300000L);
        this.checkSize = settings.getInteger("checkSize", 64);
        this.includeCustomApps = settings.getBoolean("includeCustomApps", false);

        if (controller.isActive()) {
            long checkPeriod = settings.getLong("checkPeriod", 300000L);
            vertx.setPeriodic(checkPeriod, checkPeriod, ignore -> vertx.executeBlocking(this::checkApplications));
        }
    }

    public static boolean hasDeploymentAccess(ProxyContext context, ResourceDescriptor resource) {
        if (resource.getBucketLocation().contains(DEPLOYMENTS_NAME)) {
            String location = BucketBuilder.buildInitiatorBucket(context);
            String reviewLocation = location + DEPLOYMENTS_NAME + ResourceDescriptor.PATH_SEPARATOR;
            return resource.getBucketLocation().startsWith(reviewLocation);
        }

        return false;
    }

    public List<Application> getAllApplications(ProxyContext context) {
        List<Application> applications = new ArrayList<>();
        applications.addAll(getPrivateApplications(context));
        applications.addAll(getSharedApplications(context));
        applications.addAll(getPublicApplications(context));
        return applications;
    }

    public List<Application> getPrivateApplications(ProxyContext context) {
        String location = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, bucket, location, null);
        return getApplications(folder, context);
    }

    public List<Application> getSharedApplications(ProxyContext context) {
        String location = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ListSharedResourcesRequest request = new ListSharedResourcesRequest();
        request.setResourceTypes(Set.of(ResourceTypes.APPLICATION));

        ShareService shares = context.getProxy().getShareService();
        SharedResourcesResponse response = shares.listSharedWithMe(bucket, location, request);
        Set<MetadataBase> metadata = response.getResources();

        List<Application> list = new ArrayList<>();

        for (MetadataBase meta : metadata) {
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(meta.getUrl(), encryptionService);

            if (meta instanceof ResourceItemMetadata) {
                try {
                    Application application = getApplication(resource).getValue();
                    application = ApplicationTypeSchemaUtils.filterCustomClientPropertiesWhenNoWriteAccess(context, resource, application);
                    list.add(application);
                } catch (ResourceNotFoundException ignore) {
                    // skip shared app which might be deleted incidentally
                    log.warn("Shared application is not found: {}", meta.getUrl());
                }
            } else {
                list.addAll(getApplications(resource, context));
            }
        }

        return list;
    }

    public List<Application> getPublicApplications(ProxyContext context) {
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, null);
        AccessService accessService = context.getProxy().getAccessService();
        return getApplications(folder, page -> accessService.filterForbidden(context, folder, page), context);
    }

    public Pair<ResourceItemMetadata, Application> getApplication(ResourceDescriptor resource) {
        return getApplication(resource, EtagHeader.ANY);
    }

    public Pair<ResourceItemMetadata, Application> getApplication(ResourceDescriptor resource, EtagHeader etagHeader) {
        verifyApplication(resource);
        Pair<ResourceItemMetadata, String> result = resourceService.getResourceWithMetadata(resource, etagHeader);

        if (result == null) {
            throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
        }

        ResourceItemMetadata meta = result.getKey();
        Application application = ProxyUtil.convertToObject(result.getValue(), Application.class);

        if (application == null) {
            throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
        }

        return Pair.of(meta, application);
    }

    public List<Application> getApplications(ResourceDescriptor resource, ProxyContext ctx) {
        Consumer<ResourceFolderMetadata> noop = ignore -> {
        };
        return getApplications(resource, noop, ctx);
    }

    public List<Application> getApplications(ResourceDescriptor resource,
                                             Consumer<ResourceFolderMetadata> filter, ProxyContext ctx) {
        if (!resource.isFolder() || resource.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Invalid application folder: " + resource.getUrl());
        }

        List<Application> applications = new ArrayList<>();
        String nextToken = null;

        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(resource, nextToken, PAGE_SIZE, true);
            if (folder == null) {
                break;
            }

            filter.accept(folder);

            for (MetadataBase meta : folder.getItems()) {
                if (meta.getNodeType() == NodeType.ITEM && meta.getResourceType() == ResourceTypes.APPLICATION) {
                    try {
                        ResourceDescriptor item = ResourceDescriptorFactory.fromAnyUrl(meta.getUrl(), encryptionService);
                        Application application = getApplication(item).getValue();
                        application = ApplicationTypeSchemaUtils.filterCustomClientPropertiesWhenNoWriteAccess(ctx, item, application);
                        applications.add(application);
                    } catch (ResourceNotFoundException ignore) {
                        // deleted while fetching
                    }
                }
            }

            nextToken = folder.getNextToken();
        } while (nextToken != null);

        return applications;
    }

    public Pair<ResourceItemMetadata, Application> putApplication(ResourceDescriptor resource, EtagHeader etag, String author, Application application) {
        prepareApplication(resource, application);

        ResourceItemMetadata meta = resourceService.computeResource(resource, etag, author, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class);
            Application.Function function = application.getFunction();

            if (application.getApplicationTypeSchemaId() != null && existing != null
                    && existing.getApplicationProperties() != null && application.getApplicationProperties() == null) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "The application with schema can not be updated to the one without properties");
            }

            if (function != null) {
                if (existing == null || existing.getFunction() == null) {
                    if (isPublicOrReview(resource)) {
                        throw new HttpException(HttpStatus.CONFLICT, "The application function cannot be created in public/review bucket");
                    }

                    function.setId(UrlUtil.encodePathSegment(idGenerator.get()));
                    function.setAuthorBucket(resource.getBucketName());
                    function.setStatus(Application.Function.Status.UNDEPLOYED);
                    function.setTargetFolder(encodeTargetFolder(resource, function.getId()));
                } else {
                    if (isPublicOrReview(resource) && !function.getSourceFolder().equals(existing.getFunction().getSourceFolder())) {
                        throw new HttpException(HttpStatus.CONFLICT, "The application function source folder cannot be updated in public/review bucket");
                    }
                    application.setEndpoint(existing.getEndpoint());
                    application.getFeatures().setRateEndpoint(existing.getFeatures().getRateEndpoint());
                    application.getFeatures().setTokenizeEndpoint(existing.getFeatures().getTokenizeEndpoint());
                    application.getFeatures().setTruncatePromptEndpoint(existing.getFeatures().getTruncatePromptEndpoint());
                    application.getFeatures().setConfigurationEndpoint(existing.getFeatures().getConfigurationEndpoint());
                    function.setId(existing.getFunction().getId());
                    function.setAuthorBucket(existing.getFunction().getAuthorBucket());
                    function.setStatus(existing.getFunction().getStatus());
                    function.setTargetFolder(existing.getFunction().getTargetFolder());
                    function.setError(existing.getFunction().getError());
                }
            }

            return ProxyUtil.convertToString(application);
        });

        return Pair.of(meta, application);
    }

    public void deleteApplication(ResourceDescriptor resource, EtagHeader etag) {
        verifyApplication(resource);
        MutableObject<Application> reference = new MutableObject<>();

        resourceService.computeResource(resource, etag, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class);

            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (isActive(application)) {
                throw new HttpException(HttpStatus.CONFLICT, "Application must be stopped: " + resource.getUrl());
            }

            reference.setValue(application);
            return null;
        });

        Application application = reference.getValue();

        if (isPublicOrReview(resource) && application.getFunction() != null) {
            deleteFolder(application.getFunction().getSourceFolder());
        }
    }

    public void copyApplication(ResourceDescriptor source, ResourceDescriptor destination, boolean overwrite, Consumer<Application> consumer) {
        verifyApplication(source);
        verifyApplication(destination);

        Pair<ResourceItemMetadata, Application> result = getApplication(source);
        Application application = result.getValue();
        String author = result.getKey().getAuthor();
        Application.Function function = application.getFunction();

        EtagHeader etag = overwrite ? EtagHeader.ANY : EtagHeader.NEW_ONLY;
        consumer.accept(application);
        application.setName(destination.getUrl());

        boolean isPublicOrReview = isPublicOrReview(destination);
        String sourceFolder = (function == null) ? null : function.getSourceFolder();

        resourceService.computeResource(destination, etag, author, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class);

            if (function != null) {
                if (existing == null || existing.getFunction() == null) {
                    function.setId(UrlUtil.encodePathSegment(idGenerator.get()));
                    function.setStatus(Application.Function.Status.UNDEPLOYED);
                    function.setTargetFolder(encodeTargetFolder(destination, function.getId()));

                    if (isPublicOrReview) {
                        function.setSourceFolder(function.getTargetFolder());
                    } else {
                        function.setAuthorBucket(destination.getBucketName());
                    }
                } else {
                    if (isPublicOrReview) {
                        throw new HttpException(HttpStatus.CONFLICT, "The application function must be deleted in public/review bucket");
                    }
                    application.setEndpoint(existing.getEndpoint());
                    application.getFeatures().setRateEndpoint(existing.getFeatures().getRateEndpoint());
                    application.getFeatures().setTokenizeEndpoint(existing.getFeatures().getTokenizeEndpoint());
                    application.getFeatures().setTruncatePromptEndpoint(existing.getFeatures().getTruncatePromptEndpoint());
                    application.getFeatures().setConfigurationEndpoint(existing.getFeatures().getConfigurationEndpoint());
                    function.setId(existing.getFunction().getId());
                    function.setAuthorBucket(existing.getFunction().getAuthorBucket());
                    function.setStatus(existing.getFunction().getStatus());
                    function.setTargetFolder(existing.getFunction().getTargetFolder());
                    function.setError(existing.getFunction().getError());
                }
            }

            return ProxyUtil.convertToString(application);
        });

        // for public/review application source folder is equal to target folder
        // source files are copied to read-only deployment bucket for such applications
        if (isPublicOrReview && function != null) {
            copyFolder(sourceFolder, function.getSourceFolder(), false);
        }
    }

    public Application redeployApplication(ProxyContext context, ResourceDescriptor resource) {
        verifyApplication(resource);
        controller.verifyActive();

        Pair<Application, Future<Void>> result = undeployApplicationInternal(resource);

        result.getValue().map(ignore -> deployApplication(context, resource))
                .onFailure(error -> log.error("Application redeployment is failed due to the error", error));
        return result.getKey();
    }

    public Application deployApplication(ProxyContext context, ResourceDescriptor resource) {
        verifyApplication(resource);
        controller.verifyActive();

        MutableObject<Application> result = new MutableObject<>();
        resourceService.computeResource(resource, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class);
            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (application.getFunction() == null) {
                throw new HttpException(HttpStatus.CONFLICT, "Application does not have function: " + resource.getUrl());
            }

            if (isActive(application)) {
                throw new HttpException(HttpStatus.CONFLICT, "Application must be stopped: " + resource.getUrl());
            }

            application.getFunction().setStatus(Application.Function.Status.DEPLOYING);
            application.getFunction().setError(null);

            result.setValue(application);
            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());

            return ProxyUtil.convertToString(application);
        });

        vertx.executeBlocking(() -> launchApplication(context, resource), false)
                .onFailure(error -> vertx.executeBlocking(() -> terminateApplication(resource, error.getMessage()), false));

        return result.getValue();
    }

    public Application undeployApplication(ResourceDescriptor resource) {
        return undeployApplicationInternal(resource).getKey();
    }

    private Pair<Application, Future<Void>> undeployApplicationInternal(ResourceDescriptor resource) {
        verifyApplication(resource);
        controller.verifyActive();

        MutableObject<Application> result = new MutableObject<>();
        resourceService.computeResource(resource, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class);
            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (application.getFunction() == null) {
                throw new HttpException(HttpStatus.CONFLICT, "Application does not have function: " + resource.getUrl());
            }

            if (application.getFunction().getStatus() != Application.Function.Status.DEPLOYED) {
                throw new HttpException(HttpStatus.CONFLICT, "Application is not started: " + resource.getUrl());
            }

            application.setEndpoint(null);
            application.getFeatures().setRateEndpoint(null);
            application.getFeatures().setTokenizeEndpoint(null);
            application.getFeatures().setTruncatePromptEndpoint(null);
            application.getFeatures().setConfigurationEndpoint(null);
            application.getFunction().setStatus(Application.Function.Status.UNDEPLOYING);

            result.setValue(application);
            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());

            return ProxyUtil.convertToString(application);
        });

        Future<Void> future = vertx.executeBlocking(() -> terminateApplication(resource, null), false);
        return Pair.of(result.getValue(), future);
    }

    public Application.Logs getApplicationLogs(ResourceDescriptor resource) {
        verifyApplication(resource);
        controller.verifyActive();

        Application application = getApplication(resource).getValue();

        if (application.getFunction() == null || application.getFunction().getStatus() != Application.Function.Status.DEPLOYED) {
            throw new HttpException(HttpStatus.CONFLICT, "Application is not started: " + resource.getUrl());
        }

        return controller.getApplicationLogs(application.getFunction());
    }

    private void prepareApplication(ResourceDescriptor resource, Application application) {
        verifyApplication(resource);

        if (application.getApplicationTypeSchemaId() != null) {
            if (application.getEndpoint() != null || application.getFunction() != null) {
                throw new IllegalArgumentException("Endpoint must not be set for custom application");
            }
        } else if (application.getEndpoint() == null && application.getFunction() == null) {
            throw new IllegalArgumentException("Application endpoint or function must be provided");
        }

        application.setName(resource.getUrl());
        application.setUserRoles(null);
        application.setForwardAuthToken(false);

        if (application.getReference() == null) {
            application.setReference(ApplicationUtil.generateReference());
        }

        Application.Function function = application.getFunction();
        if (function != null) {
            if (application.getFeatures() == null) {
                application.setFeatures(new Features());
            }

            application.setEndpoint(null);
            application.getFeatures().setRateEndpoint(null);
            application.getFeatures().setTokenizeEndpoint(null);
            application.getFeatures().setTruncatePromptEndpoint(null);
            application.getFeatures().setConfigurationEndpoint(null);
            function.setAuthorBucket(resource.getBucketName());
            function.setError(null);

            if (function.getRuntime() == null) {
                throw new IllegalArgumentException("Application function runtime must be provided");
            }

            if (function.getEnv() == null) {
                function.setEnv(Map.of());
            }

            if (function.getMapping() == null) {
                throw new IllegalArgumentException("Application function mapping must be provided");
            }

            verifyMapping(function.getMapping().getChatCompletion(), true, "Application chat_completion mapping is missing/invalid");
            verifyMapping(function.getMapping().getRate(), false, "Application rate mapping is invalid");
            verifyMapping(function.getMapping().getTokenize(), false, "Application tokenize mapping is invalid");
            verifyMapping(function.getMapping().getTruncatePrompt(), false, "Application truncate_prompt mapping is invalid");
            verifyMapping(function.getMapping().getConfiguration(), false, "Application configuration mapping is invalid");

            if (function.getSourceFolder() == null) {
                throw new IllegalArgumentException("Application function source folder must be provided");
            }

            try {
                ResourceDescriptor folder = ResourceDescriptorFactory.fromAnyUrl(function.getSourceFolder(), encryptionService);

                if (!folder.isFolder() || folder.getType() != ResourceTypes.FILE || !folder.getBucketName().equals(resource.getBucketName())) {
                    throw new IllegalArgumentException();
                }

                function.setSourceFolder(folder.getUrl());
            } catch (Throwable e) {
                throw new IllegalArgumentException("Application function sources must be a valid file folder: " + function.getSourceFolder());
            }
        }
    }

    private Void checkApplications() {
        log.debug("Checking pending applications");
        try {
            long now = System.currentTimeMillis();

            for (String redisKey : pendingApplications.valueRange(Double.NEGATIVE_INFINITY, true, now, true, 0, checkSize)) {
                log.debug("Checking pending application: {}", redisKey);
                ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(redisKey, encryptionService);

                try {
                    terminateApplication(resource, "Application failed to start in the specified interval");
                } catch (Throwable e) {
                    // ignore
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to check pending applications:", e);
        }

        return null;
    }

    private Void launchApplication(ProxyContext context, ResourceDescriptor resource) {
        // right now there is no lock watchdog mechanism
        // this lock can expire before this operation is finished
        // for extra safety the controller timeout is less than lock timeout
        try (LockService.Lock lock = lockService.tryLock(deploymentLockKey(resource))) {
            if (lock == null) {
                throw new IllegalStateException("Application function is locked");
            }

            Application application = getApplication(resource).getValue();
            Application.Function function = application.getFunction();

            if (function == null) {
                throw new IllegalStateException("Application has no function");
            }

            if (function.getStatus() != Application.Function.Status.DEPLOYING) {
                throw new IllegalStateException("Application is not starting");
            }

            // for public/review application source folder is equal to target folder
            // source files are copied to read-only deployment bucket for such applications
            if (!isPublicOrReview(resource)) {
                copyFolder(function.getSourceFolder(), function.getTargetFolder(), false);
            }

            controller.createApplicationImage(context, function);
            String endpoint = controller.createApplicationDeployment(context, function);

            resourceService.computeResource(resource, json -> {
                Application existing = ProxyUtil.convertToObject(json, Application.class);
                if (existing == null || !Objects.equals(existing.getFunction(), application.getFunction())) {
                    throw new IllegalStateException("Application function has been updated");
                }

                function.setStatus(Application.Function.Status.DEPLOYED);
                existing.setFunction(function);
                existing.setEndpoint(buildMapping(endpoint, function.getMapping().getChatCompletion()));
                existing.getFeatures().setRateEndpoint(buildMapping(endpoint, function.getMapping().getRate()));
                existing.getFeatures().setTokenizeEndpoint(buildMapping(endpoint, function.getMapping().getTokenize()));
                existing.getFeatures().setTruncatePromptEndpoint(buildMapping(endpoint, function.getMapping().getTruncatePrompt()));
                existing.getFeatures().setConfigurationEndpoint(buildMapping(endpoint, function.getMapping().getConfiguration()));

                return ProxyUtil.convertToString(existing);
            });

            pendingApplications.remove(resource.getUrl());
            return null;
        } catch (Throwable error) {
            log.warn("Failed to launch application: {}", resource.getUrl(), error);
            throw error;
        }
    }

    private Void terminateApplication(ResourceDescriptor resource, String error) {
        try (LockService.Lock lock = lockService.tryLock(deploymentLockKey(resource))) {
            if (lock == null) {
                return null;
            }

            Application application;

            try {
                application = getApplication(resource).getValue();
            } catch (ResourceNotFoundException e) {
                application = null;
            }

            if (isPending(application)) {
                Application.Function function = application.getFunction();

                // for public/review application source folder is equal to target folder
                // source files are copied to read-only deployment bucket for such applications
                if (!isPublicOrReview(resource)) {
                    deleteFolder(function.getTargetFolder());
                }

                controller.deleteApplicationImage(function);
                controller.deleteApplicationDeployment(function);

                resourceService.computeResource(resource, json -> {
                    Application existing = ProxyUtil.convertToObject(json, Application.class);
                    if (existing == null || !Objects.equals(existing.getFunction(), function)) {
                        throw new IllegalStateException("Application function has been updated");
                    }

                    Application.Function.Status status = (function.getStatus() == Application.Function.Status.UNDEPLOYING)
                            ? Application.Function.Status.UNDEPLOYED
                            : Application.Function.Status.FAILED;

                    function.setStatus(status);
                    function.setError(status == Application.Function.Status.FAILED ? error : null);

                    existing.setFunction(function);
                    return ProxyUtil.convertToString(existing);
                });
            }

            pendingApplications.remove(resource.getUrl());
            return null;
        } catch (Throwable e) {
            log.warn("Failed to terminate application: {}", resource.getUrl(), e);
            throw e;
        }
    }

    private String deploymentLockKey(ResourceDescriptor resource) {
        return BlobStorageUtil.toStoragePath(lockService.getPrefix(), "deployment:" + resource.getAbsoluteFilePath());
    }

    private String encodeTargetFolder(ResourceDescriptor resource, String id) {
        String location = resource.getBucketLocation()
                + DEPLOYMENTS_NAME + ResourceDescriptor.PATH_SEPARATOR
                + id + ResourceDescriptor.PATH_SEPARATOR;

        String name = encryptionService.encrypt(location);
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, name, location, null).getUrl();
    }

    public static boolean isActive(Application application) {
        return application != null && application.getFunction() != null && application.getFunction().getStatus().isActive();
    }

    private static boolean isPending(Application application) {
        return application != null && application.getFunction() != null && application.getFunction().getStatus().isPending();
    }

    private static void verifyApplication(ResourceDescriptor resource) {
        if (resource.isFolder() || resource.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
    }

    private static void verifyMapping(String path, boolean required, String message) {
        if (path == null) {
            if (required) {
                throw new IllegalArgumentException(message);
            }

            return;
        }

        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(message);
        }

        try {
            UrlUtil.decodePath(path, true);
        } catch (Throwable e) {
            throw new IllegalArgumentException(message);
        }
    }

    private void copyFolder(String sourceFolderUrl, String targetFolderUrl, boolean overwrite) {
        ResourceDescriptor sourceFolder = ResourceDescriptorFactory.fromAnyUrl(sourceFolderUrl, encryptionService);
        ResourceDescriptor targetFolder = ResourceDescriptorFactory.fromAnyUrl(targetFolderUrl, encryptionService);
        resourceService.copyFolder(sourceFolder, targetFolder, overwrite);
    }

    private boolean deleteFolder(String folderUrl) {
        ResourceDescriptor folder = ResourceDescriptorFactory.fromAnyUrl(folderUrl, encryptionService);
        return resourceService.deleteFolder(folder);
    }

    private static String buildMapping(String endpoint, String path) {
        return (endpoint == null || path == null) ? null : (endpoint + path);
    }

    private static boolean isPublicOrReview(ResourceDescriptor resource) {
        return resource.isPublic() || PublicationService.isReviewBucket(resource);
    }
}