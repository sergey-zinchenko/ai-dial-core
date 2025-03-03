package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.JsonToStringDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Upstream {

    private String endpoint;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String key;
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    private String extraData;
    private int weight = 1;
    private int tier = 0;
}