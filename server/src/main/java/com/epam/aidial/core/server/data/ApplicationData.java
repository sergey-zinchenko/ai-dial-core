package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Application;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ApplicationData extends DeploymentData {
    {
        setObject("application");
        setScaleSettings(null);
    }

    @JsonIgnore
    private Map<String, Object> customProperties = new HashMap<>(); //all custom application properties will land there

    @JsonAnySetter
    public void setCustomProperty(String key, Object value) { //all custom application properties will land there
        customProperties.put(key, value);
    }

    @JsonAnyGetter
    public  Map<String, Object> getCustomProperty() {
        return customProperties;
    }

    @Nullable
    @JsonAlias({"customAppSchemaId", "custom_app_schema_id"})
    private URI customAppSchemaId;

    private Application.Function function;
}