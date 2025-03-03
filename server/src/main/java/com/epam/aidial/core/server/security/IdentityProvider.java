package com.epam.aidial.core.server.security;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Verification;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Collections.EMPTY_LIST;

@Slf4j
public class IdentityProvider {

    // path(s) to the claim of user roles in JWT
    private final List<String[]> rolePaths = new ArrayList<>();

    // path to the claim containing Project identity
    private final String[] projectPath;

    // Delimiter to split the roles if they are set as a single String
    private final String rolesDelimiter;

    private JwkProvider jwkProvider;

    private URL userInfoUrl;

    // in memory cache store results obtained from JWK provider
    private final ConcurrentHashMap<String, Future<JwkResult>> cache = new ConcurrentHashMap<>();

    // the name of the claim in JWT to extract user email
    private final String loggingKey;
    // random salt is used to digest user email
    private final String loggingSalt;

    private final MessageDigest sha256Digest;

    // the flag determines if user email should be obfuscated
    private final boolean obfuscateUserEmail;

    private final Vertx vertx;

    private final HttpClient client;

    // the duration is how many milliseconds success JWK result should be stored in the cache
    private final long positiveCacheExpirationMs;

    // the duration is how many milliseconds failed JWK result should be stored in the cache
    private final long negativeCacheExpirationMs;

    // the pattern is used to match if the given JWT can be verified by the current provider
    private Pattern issuerPattern;

    // the flag disables JWT verification
    private final boolean disableJwtVerification;

    private final GetUserRoleFn getUserRoleFn;

    private final String audience;

    /**
     * The path to the claim to extract user display name
     */
    private final String[] userDisplayName;

    public IdentityProvider(JsonObject settings, Vertx vertx, HttpClient client,
                            Function<String, JwkProvider> jwkProviderSupplier, GetUserRoleFunctionFactory factory) {
        if (settings == null) {
            throw new IllegalArgumentException("Identity provider settings are missed");
        }
        this.vertx = vertx;
        this.client = client;

        positiveCacheExpirationMs = settings.getLong("positiveCacheExpirationMs", TimeUnit.MINUTES.toMillis(10));
        negativeCacheExpirationMs = settings.getLong("negativeCacheExpirationMs", TimeUnit.SECONDS.toMillis(10));

        disableJwtVerification = settings.getBoolean("disableJwtVerification", false);
        String jwksUrl = settings.getString("jwksUrl");
        String userinfoEndpoint = settings.getString("userInfoEndpoint");
        boolean supportJwt = jwksUrl != null || disableJwtVerification;
        boolean supportUserInfo = userinfoEndpoint != null;

        if ((!supportJwt && !supportUserInfo) || (supportJwt && supportUserInfo)) {
            throw new IllegalArgumentException("Either jwksUrl or userinfoEndpoint must be provided or disableJwtVerification is set to true");
        } else if (supportJwt) {
            if (jwksUrl != null) {
                jwkProvider = jwkProviderSupplier.apply(jwksUrl);
            }
            String issuerPatternStr = settings.getString("issuerPattern");
            if (issuerPatternStr != null) {
                issuerPattern = Pattern.compile(issuerPatternStr);
            }
        } else {
            try {
                userInfoUrl = new URL(userinfoEndpoint);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }

        Object rolePathObj = Objects.requireNonNull(settings.getValue("rolePath"), "rolePath is missed");
        List<String> rolePathList;

        if (rolePathObj instanceof String rolePathStr) {
            getUserRoleFn =  factory.getUserRoleFn(rolePathStr);
            rolePathList = List.of(rolePathStr);
        } else if (rolePathObj instanceof JsonArray rolePathArray) {
            getUserRoleFn = null;
            rolePathList = rolePathArray.stream().map(o -> (String) o).toList();
        } else {
            throw new IllegalArgumentException("rolePath should be either String or Array");
        }

        for (String rolePath : rolePathList) {
            rolePaths.add(rolePath.split("\\."));
        }

        projectPath = getClaimPath(settings, "projectPath");
        rolesDelimiter = settings.getString("rolesDelimiter");

        loggingKey = settings.getString("loggingKey");
        if (loggingKey != null) {
            loggingSalt = Objects.requireNonNull(settings.getString("loggingSalt"), "loggingSalt is missed");
        } else {
            loggingSalt = null;
        }

        try {
            sha256Digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
        obfuscateUserEmail = settings.getBoolean("obfuscateUserEmail", true);

        audience = settings.getString("audience", null);

        userDisplayName = getClaimPath(settings, "userDisplayName");

        long period = Math.min(negativeCacheExpirationMs, positiveCacheExpirationMs);
        vertx.setPeriodic(0, period, event -> evictExpiredJwks());
    }

    private static String[] getClaimPath(JsonObject settings, String claimName) {
        return settings.containsKey(claimName) ? settings.getString(claimName).split("\\.") : null;
    }

    private void evictExpiredJwks() {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Future<JwkResult>> entry : cache.entrySet()) {
            Future<JwkResult> future = entry.getValue();
            if (future.result() != null && future.result().expirationTime() <= currentTime) {
                cache.remove(entry.getKey());
            }
        }
    }

    private List<String> extractUserRoles(Map<String, Object> map) {
        List<String> result = new ArrayList<>();
        for (String[] rolePath : rolePaths) {
            result.addAll(extractUserRoles(map, rolePath));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> extractUserRoles(Map<String, Object> map, String[] rolePath) {
        Object field = extractClaim(map, rolePath);

        if (field instanceof List list) {
            return list;
        }
        if (field instanceof String string) {
            return getRolesFromString(string);
        }
        return EMPTY_LIST;
    }

    private List<String> getRolesFromString(String rolesString) {
        if (rolesDelimiter == null) {
            return List.of(rolesString);
        }
        return Arrays.stream(rolesString.split(rolesDelimiter))
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static DecodedJWT decodeJwtToken(String encodedToken) {
        return JWT.decode(encodedToken);
    }

    private Future<JwkResult> getJwk(String kid) {
        /* The result of vertx.executeBlocking is a future that contains Vert.x context which is valid during a request
         * execution. So, if we put that future in a cache, it will contain a context from the initial request, that
         * may be invalid for further requests. For this reason, when we retrieve the future from the cache, we must
         * extract the value and put it into another future (Promise) which holds a valid context of a current request.
         * */
        Promise<JwkResult> promise = Promise.promise();
        cache.computeIfAbsent(kid, key -> vertx.executeBlocking(() -> {
            JwkResult jwkResult;
            long currentTime = System.currentTimeMillis();
            try {
                Jwk jwk = jwkProvider.get(key);
                jwkResult = new JwkResult(jwk, null, currentTime + positiveCacheExpirationMs);
            } catch (Exception e) {
                jwkResult = new JwkResult(null, e, currentTime + negativeCacheExpirationMs);
            }
            return jwkResult;
        }, false)).onSuccess(promise::complete).onFailure(promise::fail);
        return promise.future();
    }

    private Future<DecodedJWT> verifyJwt(DecodedJWT jwt) {
        String kid = jwt.getKeyId();
        Future<JwkResult> future = getJwk(kid);
        return future.map(jwkResult -> verifyJwt(jwt, jwkResult));
    }

    private DecodedJWT verifyJwt(DecodedJWT jwt, JwkResult jwkResult) {
        Exception error = jwkResult.error();
        if (error != null) {
            throw new RuntimeException(error);
        }
        Jwk jwk = jwkResult.jwk();
        try {
            Verification verification = JWT.require(Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null));
            if (audience != null) {
                verification.withAudience(audience);
            }
            return verification.build().verify(jwt);
        } catch (JwkException e) {
            throw new RuntimeException(e);
        }
    }

    private static String extractUserSub(Map<String, Object> userContext) {
        return (String) userContext.get("sub");
    }

    private static String extractStringClaim(Map<String, Object> claims, String[] path) {
        if (path == null) {
            return null;
        }
        Object field = extractClaim(claims, path);

        if (field instanceof String value) {
            return value;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object extractClaim(Map<String, Object> claims, String[] claimPath) {
        for (int i = 0; i < claimPath.length - 1; i++) {
            if (claims.get(claimPath[i]) instanceof Map next) {
                claims = next;
            } else {
                return null;
            }
        }
        return claims.get(claimPath[claimPath.length - 1]);
    }

    private String extractUserHash(String keyClaim) {
        if (keyClaim != null && obfuscateUserEmail) {
            String keyClaimWithSalt = loggingSalt + keyClaim;
            byte[] hash = sha256Digest.digest(keyClaimWithSalt.getBytes(StandardCharsets.UTF_8));

            StringBuilder hashString = new StringBuilder();
            for (byte b : hash) {
                hashString.append(String.format("%02x", b));
            }

            return hashString.toString();
        }

        return keyClaim;
    }

    /**
     * Extracts user claims from user context. Currently only strings or list of strings/primitives supported.
     * If any other type provided - claim value will not be extracted, see IdentityProviderTest.testExtractClaims_13()
     *
     * @param map - user context
     * @return map of extracted user claims
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> extractUserClaims(Map<String, Object> map) {
        Map<String, List<String>> userClaims = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String claimName = entry.getKey();
            Object claimValue = entry.getValue();
            if (claimValue instanceof String stringClaimValue) {
                userClaims.put(claimName, List.of(stringClaimValue));
            } else if (claimValue instanceof List<?> list && (list.isEmpty() || list.get(0) instanceof String)) {
                userClaims.put(claimName, (List<String>) claimValue);
            } else {
                // if claim value doesn't match supported type - add claim with empty value
                userClaims.put(claimName, List.of());
            }
        }

        return userClaims;
    }

    Future<ExtractedClaims> extractClaimsFromJwt(DecodedJWT decodedJwt) {
        if (decodedJwt == null) {
            return Future.failedFuture(new IllegalArgumentException("decoded JWT must not be null"));
        }
        if (disableJwtVerification) {
            return Future.succeededFuture(from(decodedJwt));
        }
        return verifyJwt(decodedJwt).map(this::from);
    }

    Future<ExtractedClaims> extractClaimsFromUserInfo(String accessToken) {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(userInfoUrl)
                .setMethod(HttpMethod.GET);
        Promise<ExtractedClaims> promise = Promise.promise();
        client.request(options).onFailure(promise::fail).onSuccess(request -> {
            request.putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            request.send().onFailure(promise::fail).onSuccess(response -> {
                if (response.statusCode() != 200) {
                    promise.fail(String.format("UserInfo endpoint '%s' is failed with http code %d", userInfoUrl, response.statusCode()));
                    return;
                }
                response.body().map(body -> {
                    try {
                        JsonObject json = body.toJsonObject();
                        from(accessToken, json, promise);
                    } catch (Throwable e) {
                        promise.fail(e);
                    }
                    return null;
                }).onFailure(promise::fail);
            });
        });
        return promise.future().onFailure(error -> log.warn(String.format("Can't extract claims from user info endpoint '%s':", userInfoUrl), error));
    }

    private ExtractedClaims from(DecodedJWT jwt) {
        String userKey = jwt.getClaim(loggingKey).asString();
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Claim> e : jwt.getClaims().entrySet()) {
            map.put(e.getKey(), e.getValue().as(Object.class));
        }
        return new ExtractedClaims(extractUserSub(map), extractUserRoles(map), extractUserHash(userKey),
                extractUserClaims(map), extractStringClaim(map, projectPath), extractStringClaim(map, userDisplayName));
    }

    private void from(String accessToken, JsonObject userInfo, Promise<ExtractedClaims> promise) {
        String userKey = loggingKey == null ? null : userInfo.getString(loggingKey);
        Map<String, Object> map = userInfo.getMap();
        if (getUserRoleFn != null) {
            getUserRoleFn.apply(accessToken, map).onFailure(promise::fail).onSuccess(roles -> {
                ExtractedClaims extractedClaims = new ExtractedClaims(extractUserSub(map), roles, extractUserHash(userKey),
                        extractUserClaims(map), extractStringClaim(map, projectPath), extractStringClaim(map, userDisplayName));
                promise.complete(extractedClaims);
            });
        } else {
            ExtractedClaims extractedClaims =
                    new ExtractedClaims(extractUserSub(map), extractUserRoles(map), extractUserHash(userKey),
                            extractUserClaims(map), extractStringClaim(map, projectPath), extractStringClaim(map, userDisplayName));
            promise.complete(extractedClaims);
        }
    }

    boolean match(DecodedJWT jwt) {
        if (issuerPattern == null) {
            return false;
        }
        String issuer = jwt.getIssuer();
        return issuerPattern.matcher(issuer).matches();
    }

    boolean hasUserinfoUrl() {
        return userInfoUrl != null;
    }

    private record JwkResult(Jwk jwk, Exception error, long expirationTime) {
    }
}
