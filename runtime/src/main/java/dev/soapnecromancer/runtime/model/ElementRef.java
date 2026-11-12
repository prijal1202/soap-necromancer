package dev.soapnecromancer.runtime.model;

/** A top-level schema element used as a SOAP body payload. */
public record ElementRef(String namespace, String localName, String typeId) {
    public ElementRef {
        namespace = namespace == null ? "" : namespace;
    }
}
