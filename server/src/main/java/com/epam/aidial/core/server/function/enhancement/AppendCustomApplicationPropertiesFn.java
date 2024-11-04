package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.CustomApplicationPropertiesUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class AppendCustomApplicationPropertiesFn extends BaseRequestFunction<ObjectNode> {
    public AppendCustomApplicationPropertiesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        try {
            if (appendCustomProperties(context, tree)) {
                context.setRequestBody(Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)));
            }
            return null;
        } catch (Throwable e) {
            context.respond(HttpStatus.BAD_REQUEST);
            log.warn("Can't append server properties to deployment {}. Trace: {}. Span: {}. Error: {}",
                    context.getDeployment().getName(), context.getTraceId(), context.getSpanId(), e.getMessage());
            return e;
        }
    }

    private static boolean appendCustomProperties(ProxyContext context, ObjectNode tree) throws JsonProcessingException {
        Deployment deployment = context.getDeployment();
        if (!(deployment instanceof Application application && application.getCustomAppSchemaId() != null)) {
            return false;
        }
        boolean appended = false;
        Map<String, Object> props = CustomApplicationPropertiesUtils.getCustomClientProperties(context.getConfig(), application);
        ObjectNode customAppPropertiesNode = ProxyUtil.MAPPER.createObjectNode();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            customAppPropertiesNode.put(entry.getKey(), entry.getValue().toString());
        }
        tree.set("custom_application_properties", customAppPropertiesNode);
        return appended;
    }
}
