package dev.soapnecromancer.runtime.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical model of one SOAP service, produced by the generator's parser and
 * shipped in every
 * generated proxy. {@code types} keeps insertion order so output is
 * deterministic.
 * {@code soapVersion} is the version of the one binding the model was built
 * from; a model.json
 * written before SOAP 1.2 support has no such key and reads as SOAP 1.1.
 */
public record ServiceModel(String name, String targetNamespace, String endpointUrl, SoapVersion soapVersion,
        List<OperationModel> operations, Map<String, TypeModel> types) {

    public ServiceModel {
        soapVersion = soapVersion == null ? SoapVersion.SOAP_11 : soapVersion;
        operations = List.copyOf(operations);
        types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
    }

    /** A SOAP 1.1 service. */
    public ServiceModel(String name, String targetNamespace, String endpointUrl,
            List<OperationModel> operations, Map<String, TypeModel> types) {
        this(name, targetNamespace, endpointUrl, SoapVersion.SOAP_11, operations, types);
    }

    public TypeModel type(String id) {
        TypeModel t = types.get(id);
        if (t == null)
            throw new IllegalArgumentException("Unknown type id: " + id);
        return t;
    }

    public OperationModel operation(String name) {
        for (OperationModel op : operations) {
            if (op.name().equals(name))
                return op;
        }
        throw new IllegalArgumentException("Unknown operation: " + name);
    }
}
