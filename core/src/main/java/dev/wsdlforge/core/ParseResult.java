package dev.wsdlforge.core;

import dev.wsdlforge.runtime.model.ServiceModel;

import java.util.List;

public record ParseResult(ServiceModel model, List<Warning> warnings) {
    public ParseResult {
        warnings = List.copyOf(warnings);
    }
}
