package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@UtilityClass
@Slf4j
public class ProxyUtil {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private static final MultiMap TRACE_HEADERS = MultiMap.caseInsensitiveMultiMap()
            .add("traceparent", "whatever")
            .add("tracestate", "whatever");
    private static final MultiMap HOP_BY_HOP_HEADERS = MultiMap.caseInsensitiveMultiMap()
            .add(HttpHeaders.CONNECTION, "whatever")
            .add(HttpHeaders.KEEP_ALIVE, "whatever")
            .add(HttpHeaders.HOST, "whatever")
            .add(HttpHeaders.PROXY_AUTHENTICATE, "whatever")
            .add(HttpHeaders.PROXY_AUTHORIZATION, "whatever")
            .add("te", "whatever")
            .add("trailer", "whatever")
            .add(HttpHeaders.TRANSFER_ENCODING, "whatever")
            .add(HttpHeaders.UPGRADE, "whatever")
            .add(HttpHeaders.CONTENT_LENGTH, "whatever")
            .add(HttpHeaders.ACCEPT_ENCODING, "whatever")
            .add(Proxy.HEADER_API_KEY, "whatever");
    public static final String METADATA_PREFIX = "metadata/";

    public static void copyHeaders(MultiMap from, MultiMap to) {
        copyHeaders(from, to, MultiMap.caseInsensitiveMultiMap());
    }

    public static void copyHeaders(MultiMap from, MultiMap to, MultiMap excludeHeaders) {
        for (Map.Entry<String, String> entry : from.entries()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (!HOP_BY_HOP_HEADERS.contains(key) && !TRACE_HEADERS.contains(key) && !excludeHeaders.contains(key)) {
                to.add(key, value);
            }
        }
    }

    public static int contentLength(HttpServerRequest request, int defaultValue) {
        return contentLength(request.headers(), defaultValue);
    }

    public static int contentLength(HttpClientResponse request, int defaultValue) {
        MultiMap header = request.headers();
        return contentLength(header, defaultValue);
    }

    private static int contentLength(MultiMap header, int defaultValue) {
        String text = header.get(HttpHeaders.CONTENT_LENGTH);
        if (text != null) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    public static void collectAttachmentsFromResponse(ObjectNode tree, boolean isStream, Consumer<String> consumer) {
        ArrayNode choices = (ArrayNode) tree.get("choices");
        if (choices == null) {
            return;
        }
        for (int i = 0; i < choices.size(); i++) {
            JsonNode choice = choices.get(i);
            String messageNodeName = isStream ? "delta" : "message";
            JsonNode message = choice.get(messageNodeName);
            if (message == null) {
                continue;
            }
            JsonNode customContent = message.get("custom_content");
            if (customContent == null) {
                continue;
            }
            ArrayNode attachments = (ArrayNode) customContent.get("attachments");
            if (attachments != null) {
                for (int j = 0; j < attachments.size(); j++) {
                    JsonNode attachment = attachments.get(j);
                    collectAttachedFile(attachment, consumer);
                }
            }
            ArrayNode stages = (ArrayNode) customContent.get("stages");
            if (stages != null) {
                for (int j = 0; j < stages.size(); j++) {
                    JsonNode stage = stages.get(j);
                    attachments = (ArrayNode) stage.get("attachments");
                    if (attachments == null) {
                        continue;
                    }
                    for (int k = 0; k < attachments.size(); k++) {
                        JsonNode attachment = attachments.get(k);
                        collectAttachedFile(attachment, consumer);
                    }
                }
            }
        }
    }

    public static void collectAttachedFilesFromRequest(ObjectNode tree, Consumer<String> consumer) {
        collectAttachedFilesChatCompletion(tree, consumer);
        collectAttachedFilesEmbeddings(tree, consumer);
    }

    private static void collectAttachedFilesEmbeddings(ObjectNode tree, Consumer<String> consumer) {
        JsonNode inputs = tree.get("custom_input");

        if (inputs == null) {
            return;
        }

        if (inputs.isArray()) {
            for (int i = 0; i < inputs.size(); i++) {
                collectAttachedFilesCustomInput(inputs.get(i), consumer);
            }
        }
    }

    private static void collectAttachedFilesCustomInput(JsonNode input, Consumer<String> consumer) {
        if (input.isObject()) {
            collectAttachedFile(input, consumer);
        } else if (input.isArray()) {
            for (int i = 0; i < input.size(); i++) {
                collectAttachedFilesCustomInput(input.get(i), consumer);
            }
        }
    }

    private static void collectAttachedFilesFromContent(JsonNode content, Consumer<String> consumer) {
        if (!(content instanceof ArrayNode contentParts)) {
            return;
        }

        for (int i = 0; i < contentParts.size(); i++) {
            JsonNode partNode = contentParts.get(i);
            JsonNode partTypeNode = partNode.get("type");
            if (partTypeNode == null || !partTypeNode.textValue().equals("image_url")) {
                continue;
            }
            JsonNode imageNode = partNode.get("image_url");
            if (imageNode == null) {
                continue;
            }
            JsonNode urlNode = imageNode.get("url");
            collectAttachedFilesFromUrl(urlNode, null, consumer);
        }
    }

    private static void collectAttachedFilesFromCustomContent(JsonNode customContent, Consumer<String> consumer) {
        if (customContent == null) {
            return;
        }
        ArrayNode attachments = (ArrayNode) customContent.get("attachments");
        if (attachments != null) {
            for (int i = 0; i < attachments.size(); i++) {
                JsonNode attachment = attachments.get(i);
                collectAttachedFile(attachment, consumer);
            }
        }
        ArrayNode stages = (ArrayNode) customContent.get("stages");
        if (stages != null) {
            for (int i = 0; i < stages.size(); i++) {
                JsonNode stage = stages.get(i);
                attachments = (ArrayNode) stage.get("attachments");
                if (attachments == null) {
                    continue;
                }
                for (int j = 0; j < attachments.size(); j++) {
                    JsonNode attachment = attachments.get(j);
                    collectAttachedFile(attachment, consumer);
                }
            }
        }
    }

    private static void collectAttachedFilesChatCompletion(ObjectNode tree, Consumer<String> consumer) {
        ArrayNode messages = (ArrayNode) tree.get("messages");
        if (messages == null) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            JsonNode content = message.get("content");
            collectAttachedFilesFromContent(content, consumer);

            JsonNode customContent = message.get("custom_content");
            collectAttachedFilesFromCustomContent(customContent, consumer);
        }
    }

    private static void collectAttachedFilesFromUrl(JsonNode urlNode, JsonNode typeNode, Consumer<String> consumer) {
        if (urlNode == null) {
            return;
        }

        String url = urlNode.textValue();

        if (url == null) {
            return;
        }

        if (typeNode != null && typeNode.textValue().equals(MetadataBase.MIME_TYPE)) {
            if (!url.startsWith(METADATA_PREFIX)) {
                throw new IllegalArgumentException("Url of metadata attachment must start with metadata/: " + url);
            }
            url = url.substring(METADATA_PREFIX.length());
        }

        consumer.accept(url);
    }

    private static void collectAttachedFile(JsonNode attachment, Consumer<String> consumer) {
        JsonNode urlNode = attachment.get("url");
        JsonNode typeNode = attachment.get("type");
        collectAttachedFilesFromUrl(urlNode, typeNode, consumer);
    }

    public static <T> T convertToObject(Buffer json, Class<T> clazz) {
        try {
            String text = json.toString(StandardCharsets.UTF_8);
            return MAPPER.readValue(text, clazz);
        } catch (Throwable e) {
            throw new IllegalArgumentException("Failed to parse json: " + e.getMessage());
        }
    }

    @Nullable
    public static <T> T convertToObject(String payload, TypeReference<T> type) {
        if (payload == null) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Nullable
    public static <T> T convertToObject(String payload, Class<T> clazz) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert payload to the object", e);
            if (e instanceof MismatchedInputException mismatchedInputException && mismatchedInputException.getPath() != null && !mismatchedInputException.getPath().isEmpty()) {
                String missingField = mismatchedInputException.getPath().stream()
                        .map(JsonMappingException.Reference::getFieldName)
                        .collect(Collectors.joining("."));
                throw new IllegalArgumentException("Missing required property '%s'".formatted(missingField));
            }
            throw new IllegalArgumentException("Provided payload do not match required schema");
        }
    }

    @Nullable
    public static String convertToString(Object data) {
        if (data == null) {
            return null;
        }

        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> boolean processChain(T item, List<BaseRequestFunction<T>> chain) {
        boolean result = false;
        for (BaseRequestFunction<T> fn : chain) {
            if (fn.apply(item)) {
                result = true;
            }
        }
        return result;
    }

    public static EtagHeader etag(HttpServerRequest request) {
        return EtagHeader.fromHeader(request.getHeader(HttpHeaders.IF_MATCH), request.getHeader(HttpHeaders.IF_NONE_MATCH), request.method().name());
    }
}
