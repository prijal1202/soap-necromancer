package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.model.ServiceModel;

import java.util.List;

public record ParseResult(ServiceModel model, List<Warning> warnings) {
    public ParseResult {
        warnings = List.copyOf(warnings);
    }
}
