package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Invitation {
    String id;
    List<SharedResource> resources;
    long createdAt;
    long expireAt;
    String author;

    @JsonCreator
    public Invitation(
            @JsonProperty("id") String id,
            @JsonProperty("resources") List<SharedResource> resources,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("expireAt") long expireAt,
            @JsonProperty("author") String author) {
        this.id = id;
        this.resources = resources.stream()
                .map(SharedResource::withReadIfNoPermissions)
                .collect(Collectors.toList());
        this.createdAt = createdAt;
        this.expireAt = expireAt;
        this.author = author;
    }
}
