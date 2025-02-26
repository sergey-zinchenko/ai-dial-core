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

import java.util.Map;

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
        Map<String, Object> props = ApplicationTypeSchemaUtils.getCustomServerProperties(context.getConfig(), application);
        context.getProxyRequest().putHeader(HEADER_APPLICATION_PROPERTIES, ProxyUtil.MAPPER.writeValueAsString(props));
        return false;
    }
}
