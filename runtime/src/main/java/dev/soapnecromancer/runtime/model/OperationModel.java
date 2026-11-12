package dev.soapnecromancer.runtime.model;

/** One document/literal SOAP operation. {@code documentation} and {@code soapAction} may be empty. */
public record OperationModel(String name, String soapAction, String documentation, ElementRef input, ElementRef output) {
    public OperationModel {
        soapAction = soapAction == null ? "" : soapAction;
        documentation = documentation == null ? "" : documentation;
    }
}
