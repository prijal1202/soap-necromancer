package dev.soapnecromancer.core;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.soapnecromancer.runtime.model.ServiceModel;

/**
 * Deterministic JSON for model.json and generator-report.json. Record
 * components only.
 */
public final class ModelJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .visibility(PropertyAccessor.IS_GETTER, Visibility.NONE)
            .visibility(PropertyAccessor.GETTER, Visibility.NONE)
            .visibility(PropertyAccessor.FIELD, Visibility.ANY)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private ModelJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String write(ServiceModel model) {
        return write((Object) model);
    }
}
