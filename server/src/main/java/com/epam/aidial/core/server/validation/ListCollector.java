package com.epam.aidial.core.server.validation;

import com.networknt.schema.Collector;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

public class ListCollector<T> implements Collector<List<T>> {

    @Getter
    public enum FileCollectorType {
        ALL_FILES("file"),
        ONLY_SERVER_FILES("server_file");

        private final String value;

        FileCollectorType(String value) {
            this.value = value;
        }
    }

    private final List<T> references = new ArrayList<>();

    @Override
    @SuppressWarnings("unchecked")
    public void combine(Object o) {
        if (!(o instanceof List)) {
            return;
        }
        List<T> list = (List<T>) o;
        references.addAll(list);
    }

    @Override
    public List<T> collect() {
        return references;
    }
}

