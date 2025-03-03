package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class Limit {
    private long minute = Long.MAX_VALUE;
    private long day = Long.MAX_VALUE;
    private long week = Long.MAX_VALUE;
    private long month = Long.MAX_VALUE;
    private long requestHour = Long.MAX_VALUE;
    private long requestDay = Long.MAX_VALUE;

    @JsonIgnore
    public boolean isPositive() {
        return minute > 0 && day > 0 && week > 0 && month > 0 && requestDay > 0 && requestHour > 0;
    }
}
