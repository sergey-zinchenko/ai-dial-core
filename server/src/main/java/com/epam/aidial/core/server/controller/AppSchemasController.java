package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class AppSchemasController implements Controller {
    private final ProxyContext context;

    public AppSchemasController(ProxyContext context) {
        this.context = context;
    }

    private static final String LIST_SCHEMAS_RELATIVE_PATH = "list";
    private static final String META_SCHEMA_RELATIVE_PATH = "schema";

    @Override
    public Future<?> handle() {
        HttpServerRequest request = context.getRequest();
        String path = request.path();
        if (path.endsWith(LIST_SCHEMAS_RELATIVE_PATH)) {
            return handleListSchemas();
        } else if (path.endsWith(META_SCHEMA_RELATIVE_PATH)) {
            return handleGetMetaSchema();
        } else {
            return handleGetSchema();
        }
    }

    private static final String FAILED_READ_META_SCHEMA_MESSAGE = "Failed to read meta-schema from resources";


    private Future<?> handleGetMetaSchema() {
        try {
            return context.respond(HttpStatus.OK, MetaSchemaHolder.getCustomApplicationMetaSchema());
        } catch (Throwable e) {
            log.error(FAILED_READ_META_SCHEMA_MESSAGE, e);
            return context.respond(HttpStatus.INTERNAL_SERVER_ERROR, FAILED_READ_META_SCHEMA_MESSAGE);
        }
    }

    private static final String COMPLETION_ENDPOINT_FIELD = "dial:custom-application-type-completion-endpoint";

    private Future<?> handleGetSchema() {
        HttpServerRequest request = context.getRequest();
        String schemaIdParam = request.getParam("id");

        if (schemaIdParam == null) {
            return context.respond(HttpStatus.BAD_REQUEST, "Schema ID is required");
        }

        URI schemaId;
        try {
            schemaId = URI.create(URLDecoder.decode(schemaIdParam, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return context.respond(HttpStatus.BAD_REQUEST, "Schema ID should be a valid uri");
        }

        String schema = context.getConfig().getCustomApplicationSchemas().get(schemaId.toString());
        if (schema == null) {
            return context.respond(HttpStatus.NOT_FOUND, "Schema not found");
        }

        try {
            JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);
            if (schemaNode.has(COMPLETION_ENDPOINT_FIELD)) {
                ((ObjectNode) schemaNode).remove(COMPLETION_ENDPOINT_FIELD); //we need to remove completion endpoint from response to avoid disclosure
            }
            return context.respond(HttpStatus.OK, schemaNode);
        } catch (IOException e) {
            log.error("Failed to parse schema", e);
            return context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse schema");
        }
    }

    private static final String ID_FIELD = "$id";
    private static final String EDITOR_URL_FIELD = "dial:custom-application-type-editor-url";
    private static final String DISPLAY_NAME_FIELD = "dial:custom-application-type-display-name";

    public Future<?> handleListSchemas() {
        Config config = context.getConfig();
        List<JsonNode> filteredSchemas = new ArrayList<>();

        for (Map.Entry<String, String> entry : config.getCustomApplicationSchemas().entrySet()) {
            JsonNode schemaNode;
            try {
                schemaNode = ProxyUtil.MAPPER.readTree(entry.getValue());
            } catch (IOException e) {
                log.error("Failed to parse schema", e);
                continue;
            }

            if (schemaNode.has(ID_FIELD)
                    && schemaNode.has(EDITOR_URL_FIELD)
                    && schemaNode.has(DISPLAY_NAME_FIELD)) {
                ObjectNode filteredNode = ProxyUtil.MAPPER.createObjectNode();
                filteredNode.set(ID_FIELD, schemaNode.get(ID_FIELD));
                filteredNode.set(EDITOR_URL_FIELD, schemaNode.get(EDITOR_URL_FIELD));
                filteredNode.set(DISPLAY_NAME_FIELD, schemaNode.get(DISPLAY_NAME_FIELD));
                filteredSchemas.add(filteredNode);
            }
        }

        return context.respond(HttpStatus.OK, filteredSchemas);
    }


}
