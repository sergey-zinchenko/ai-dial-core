package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;

@Slf4j
public class AppendApplicationPropertiesFn extends BaseRequestFunction<ObjectNode> {
    public AppendApplicationPropertiesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @SneakyThrows
    @Override
    public Boolean apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (!(deployment instanceof Application application && application.isCustom())) {
            return false;
        }

        context.getProxyRequest().putHeader(HEADER_APPLICATION_ID, deployment.getName());

        ApplicationTypeSchemaUtils.getCustomServerProperties(context.getConfig(), application, (properties, usePropertiesHeader) -> {
            if (usePropertiesHeader) {
                String propsString = ProxyUtil.MAPPER.writeValueAsString(properties);
                context.getProxyRequest().putHeader(HEADER_APPLICATION_PROPERTIES, propsString);
            }
        });

        return false;
    }
}
