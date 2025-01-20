package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.storage.data.ResourceAccessType;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;


@Slf4j
public class CollectRequestApplicationFilesFn extends BaseRequestFunction<ObjectNode> {
    public CollectRequestApplicationFilesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        try {
            Deployment deployment = context.getDeployment();
            if (!(deployment instanceof Application application && application.getApplicationTypeSchemaId() != null)) {
                return false;
            }
            List<ResourceDescriptor> resources = ApplicationTypeSchemaUtils.getServerFiles(context.getConfig(), application, proxy.getEncryptionService(),
                    proxy.getResourceService());
            ApiKeyData keyData = context.getProxyApiKeyData();
            appendFilesToProxyApiKeyData(keyData, resources);
            String perRequestKey = keyData.getPerRequestKey();
            if (perRequestKey == null) { //This class may be not the one who modifies the perRequestKey
                proxy.getApiKeyStore().assignPerRequestApiKey(keyData);
            } else {
                proxy.getApiKeyStore().updatePerRequestApiKey(perRequestKey, json -> ProxyUtil.convertToString(keyData));
            }
            return false;
        } catch (HttpException ex) {
            throw ex;
        } catch (ApplicationTypeResourceException ex) {
            throw new HttpException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (Exception e) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void appendFilesToProxyApiKeyData(ApiKeyData apiKeyData, List<ResourceDescriptor> resources) {
        for (ResourceDescriptor resource : resources) {
            String resourceUrl = resource.getUrl();
            AccessService accessService = proxy.getAccessService();
            if (accessService.hasReadAccess(resource, context)) {
                if (resource.isFolder()) {
                    apiKeyData.getAttachedFolders().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
                } else {
                    apiKeyData.getAttachedFiles().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
                }
            } else {
                throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the file %s".formatted(resourceUrl));
            }
        }
    }
}
