package com.epam.aidial.core.server.validation;

import com.networknt.schema.Collector;

import java.util.ArrayList;
import java.util.List;

public class ListCollector<T> implements Collector<List<T>> {
    private final List<T> references = new ArrayList<>();

    @Override
    @SuppressWarnings("unchecked")
    public void combine(Object o) {
        if (!(o instanceof List)) {
            return;
        }
        List<T> list = (List<T>) o;
        synchronized (references) {
            references.addAll(list);
        }
    }

    @Override
    public List<T> collect() {
        return references;
    }
}

