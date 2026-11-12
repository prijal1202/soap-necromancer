package dev.soapnecromancer.runtime.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical model of a SOAP service, produced by the generator's parser and embedded in every
 * generated proxy. {@code types} keeps insertion order so output is deterministic.
 */
public record ServiceModel(String name, String targetNamespace, String endpointUrl,
                           List<OperationModel> operations, Map<String, TypeModel> types) {

    public ServiceModel {
        operations = List.copyOf(operations);
        types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
    }

    public TypeModel type(String id) {
        TypeModel t = types.get(id);
        if (t == null) throw new IllegalArgumentException("Unknown type id: " + id);
        return t;
    }

    public OperationModel operation(String name) {
        for (OperationModel op : operations) {
            if (op.name().equals(name)) return op;
        }
        throw new IllegalArgumentException("Unknown operation: " + name);
    }
}
