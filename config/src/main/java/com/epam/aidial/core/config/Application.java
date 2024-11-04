package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Application extends Deployment {

    private Function function;

    @JsonIgnore
    private Map<String, Object> customProperties = Map.of();

    @JsonAnySetter
    public void setCustomProperty(String key, Object value) {
        customProperties.put(key, value);
    }

    @Nullable
    private URI customAppSchemaId;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Function {

        private String id;
        private String runtime;
        private String authorBucket;
        private String sourceFolder;
        private String targetFolder;
        private Status status;
        private String error;
        private Mapping mapping;
        private Map<String, String> env;

        public enum Status {
            CREATED, STARTING, STOPPING, STARTED, STOPPED, FAILED;

            public boolean isPending() {
                return switch (this) {
                    case CREATED, STARTED, FAILED, STOPPED -> false;
                    case STARTING, STOPPING -> true;
                };
            }

            public boolean isActive() {
                return switch (this) {
                    case CREATED, FAILED, STOPPED -> false;
                    case STARTING, STARTED, STOPPING -> true;
                };
            }
        }

        @Data
        @Accessors(chain = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public static class Mapping {
            private String completion;
            private String rate;
            private String tokenize;
            private String truncatePrompt;
            private String configuration;
        }
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Logs {
        private Collection<Log> logs;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Log {
        private String instance;
        private String content;
    }
}