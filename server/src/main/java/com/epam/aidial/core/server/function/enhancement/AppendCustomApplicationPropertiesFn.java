package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.CustomApplicationUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class AppendCustomApplicationPropertiesFn extends BaseRequestFunction<ObjectNode> {
    public AppendCustomApplicationPropertiesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (!(deployment instanceof Application application && application.getCustomAppSchemaId() != null)) {
            return false;
        }
        boolean appended = false;
        Map<String, Object> props = CustomApplicationUtils.getCustomServerProperties(context.getConfig(), application);
        ObjectNode customAppPropertiesNode = ProxyUtil.MAPPER.createObjectNode();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            customAppPropertiesNode.putPOJO(entry.getKey(), entry.getValue());
            appended = true;
        }
        tree.set("custom_application_properties", customAppPropertiesNode);
        return appended;
    }
}
