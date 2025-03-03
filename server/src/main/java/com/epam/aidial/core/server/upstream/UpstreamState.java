package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
class UpstreamState {

    static final long DEFAULT_RETRY_AFTER_SECONDS_VALUE = 30;
    @Getter
    private final Upstream upstream;

    // max backoff delay - 5 minutes
    private static final long MAX_BACKOFF_DELAY_SEC = 5 * 60;

    /**
     * Amount of 5xx errors from upstream
     */
    private int errorCount = 0;
    /**
     * Timestamp in millis when upstream may be available
     */
    @Getter
    private long retryAfter = -1;

    @Getter
    private RetryAfterSource source;

    @Getter
    private HttpStatus status;

    UpstreamState(Upstream upstream) {
        this.upstream = upstream;
    }

    /**
     * Register upstream failure.
     *
     * @param status response status code from upstream
     * @param retryAfterSeconds time in seconds when upstream may become available
     */
    void fail(HttpStatus status, long retryAfterSeconds) {
        this.source = retryAfterSeconds == -1 ? RetryAfterSource.CORE : RetryAfterSource.UPSTREAM;
        this.status = status;
        if (status.is5xx()) {
            if (source == RetryAfterSource.CORE) {
                if (errorCount != 30) {
                    errorCount++;
                }
                retryAfterSeconds = Math.min(1L << errorCount, MAX_BACKOFF_DELAY_SEC);
                setReplyAfter(retryAfterSeconds);
            } else {
                setReplyAfter(retryAfterSeconds);
            }
        } else {
            retryAfterSeconds = source == RetryAfterSource.CORE ? DEFAULT_RETRY_AFTER_SECONDS_VALUE : retryAfterSeconds;
            setReplyAfter(retryAfterSeconds);
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Upstream {} limit hit: retry after {}", upstream.getEndpoint(), Instant.ofEpochMilli(retryAfter).toString());
            }
        }
    }

    private void setReplyAfter(long retryAfterSeconds) {
        retryAfter = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(retryAfterSeconds);
    }

    /**
     * reset errors state
     */
    void succeeded() {
        // reset errors
        errorCount = 0;
        retryAfter = -1;
        source = null;
        status = null;
    }

    boolean isUpstreamAvailable() {
        if (retryAfter < 0) {
            return true;
        }

        return System.currentTimeMillis() > retryAfter;
    }

    enum RetryAfterSource {
        UPSTREAM, CORE
    }
}
