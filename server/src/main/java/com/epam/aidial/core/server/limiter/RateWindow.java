package com.epam.aidial.core.server.limiter;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public enum RateWindow {
    MINUTE(60L * 1000, 60),
    HOUR(60 * 60 * 1000, 60),
    DAY(24L * 60 * 60 * 1000, 24),
    WEEK(7L * 24 * 60 * 60 * 1000, 7),
    MONTH(30L * 24 * 60 * 60 * 1000, 30);

    private final long window;
    private final long interval;
    private final int intervals;

    RateWindow(long window, int intervals) {
        this.window = window;
        this.interval = window / intervals;
        this.intervals = intervals;
    }
}
