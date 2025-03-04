package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.AccessTokenValidator;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.epam.aidial.core.server.Proxy.HEADER_API_KEY;
import static com.epam.aidial.core.server.Proxy.HEALTH_CHECK_PATH;
import static com.epam.aidial.core.storage.blobstore.Storage.DEFAULT_MAX_UPLOADED_FILE_SIZE_BYTES;
import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.storage.http.HttpStatus.HTTP_VERSION_NOT_SUPPORTED;
import static com.epam.aidial.core.storage.http.HttpStatus.METHOD_NOT_ALLOWED;
import static com.epam.aidial.core.storage.http.HttpStatus.OK;
import static com.epam.aidial.core.storage.http.HttpStatus.REQUEST_ENTITY_TOO_LARGE;
import static com.epam.aidial.core.storage.http.HttpStatus.UNAUTHORIZED;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ProxyTest {

    @Mock
    private Vertx vertx;
    @Mock
    private HttpClient client;
    @Mock
    private ConfigStore configStore;
    @Mock
    private ApiKeyStore apiKeyStore;
    @Mock
    private LogStore logStore;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private AccessTokenValidator accessTokenValidator;
    @Mock
    private BlobStorage storage;
    @Mock
    private ResourceService resourceService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    @InjectMocks
    private Proxy proxy;

    @BeforeEach
    public void beforeEach() {
        when(resourceService.getMaxSize()).thenReturn(67108864);
        when(request.response()).thenReturn(response);
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)).thenReturn(null);
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)).thenReturn(null);
        when(response.setStatusCode(anyInt())).thenReturn(response);
    }

    @AfterEach
    public void afterEach() {
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    }

    @Test
    public void testHandle_UnsupportedHttpVersion() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_0);

        proxy.handle(request);

        verify(response).setStatusCode(HTTP_VERSION_NOT_SUPPORTED.getCode());
    }

    @Test
    public void testHandle_HttpMethodNotAllowed() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.PATCH);

        proxy.handle(request);

        verify(response).setStatusCode(METHOD_NOT_ALLOWED.getCode());
    }

    @Test
    public void testHandle_ContentBodyIsTooLarge_Multipart() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn("multipart/form-data");
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Long.toString(DEFAULT_MAX_UPLOADED_FILE_SIZE_BYTES + 1));

        proxy.handle(request);

        verify(response).setStatusCode(REQUEST_ENTITY_TOO_LARGE.getCode());
    }

    @Test
    public void testHandle_ContentBodyIsTooLarge() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Long.toString(DEFAULT_MAX_UPLOADED_FILE_SIZE_BYTES + 1));

        proxy.handle(request);

        verify(response).setStatusCode(REQUEST_ENTITY_TOO_LARGE.getCode());
    }

    @Test
    public void testHandle_HealthCheck() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn(HEALTH_CHECK_PATH);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_MissingApiKeyAndToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.path()).thenReturn("/foo");

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_ApiKeyNotFound() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        when(configStore.get()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Unknown API key")));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_ApiKeyIsNotPerRequestKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(BAD_REQUEST.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_CallerIsNotInterceptor_1() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request_key");
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(BAD_REQUEST.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_CallerIsNotInterceptor_2() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request_key");
        apiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        apiKeyData.setInterceptorIndex(2);
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(BAD_REQUEST.getCode());
    }

    @Test
    public void testHandle_BothApiKeyAndToken_CallerIsInterceptor() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer token");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("api-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);

        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request_key");
        apiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        apiKeyData.setInterceptorIndex(1);
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_UnknownApiKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("bad-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        Config config = new Config();
        config.setKeys(Map.of("key1", new Key()));
        when(configStore.get()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Api key is not found")));

        when(request.response()).thenReturn(response);
        when(response.ended()).thenReturn(false);

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_OpenAiRequestSuccess() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new RuntimeException()));
        when(apiKeyStore.getApiKeyData("key1")).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_TryAccessToken_Success() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        ExtractedClaims extractedClaims = new ExtractedClaims("sub", List.of("role1"), "hash", Map.of(), null, null);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.succeededFuture(extractedClaims));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
        verify(apiKeyStore, never()).getApiKeyData(anyString());
    }

    @Test
    public void testHandle_TryAccessToken_Failure() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/foo");

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new RuntimeException()));
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Unknown API key")));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_OpenAiRequestWrongApiKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);

        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("wrong-key");
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer wrong-key");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        Config config = new Config();
        config.setKeys(Map.of("key1", new Key()));
        when(configStore.get()).thenReturn(config);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Bad Authorization header")));
        when(apiKeyStore.getApiKeyData(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Unknown API key")));
        when(request.response()).thenReturn(response);
        when(response.ended()).thenReturn(false);

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_SuccessApiKey() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(apiKeyStore.getApiKeyData("key1")).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_AzureOpenAiRequest() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer");
        when(headers.get(eq(HEADER_API_KEY))).thenReturn("key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ApiKeyData apiKeyData = new ApiKeyData();
        Key originalKey = new Key();
        apiKeyData.setOriginalKey(originalKey);
        when(apiKeyStore.getApiKeyData("key1")).thenReturn(Future.succeededFuture(apiKeyData));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_SuccessAccessToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(request.uri()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        ExtractedClaims extractedClaims = new ExtractedClaims("sub", List.of("role1"), "hash", Map.of(), null, null);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.succeededFuture(extractedClaims));

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
    }

    @Test
    public void testHandle_WrongAccessToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("bearer key1");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");

        Config config = new Config();
        Route route = new Route();
        route.setMethods(Set.of("GET"));
        route.setName("route");
        route.setPaths(List.of(Pattern.compile("/foo")));
        route.setResponse(new Route.Response());
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        routes.put("route", route);
        config.setRoutes(routes);
        when(configStore.get()).thenReturn(config);
        when(accessTokenValidator.extractClaims(anyString())).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Bad Authorization header")));
        when(request.response()).thenReturn(response);
        when(response.ended()).thenReturn(false);

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_InvalidToken() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.GET);
        MultiMap headers = mock(MultiMap.class);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader(eq(HttpHeaders.CONTENT_TYPE))).thenReturn(null);
        when(request.getHeader(eq(HttpHeaders.AUTHORIZATION))).thenReturn("token");
        when(headers.get(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(Integer.toString(512));
        when(request.path()).thenReturn("/foo");
        when(accessTokenValidator.extractClaims(eq("token"))).thenReturn(Future.failedFuture(new HttpException(UNAUTHORIZED, "Bad Authorization header")));

        proxy.handle(request);

        verify(response).setStatusCode(UNAUTHORIZED.getCode());
    }

    @Test
    public void testHandle_OptionsRequest() {
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.OPTIONS);
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)).thenReturn("GET");
        when(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)).thenReturn("Api-Key");

        proxy.handle(request);

        verify(response).setStatusCode(OK.getCode());
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "86400");
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET");
        verify(response).putHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Api-Key");
    }
}
