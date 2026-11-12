package dev.soapnecromancer.runtime.model;

/**
 * A child element of a complex type.
 *
 * @param name      element local name, also the JSON property name
 * @param namespace element namespace; empty string when unqualified
 * @param typeId    key into {@link ServiceModel#types()}
 * @param minOccurs 0 = optional
 * @param maxOccurs {@link #UNBOUNDED} or a positive bound; greater than 1 means a JSON array
 */
public record FieldModel(String name, String namespace, String typeId, int minOccurs, int maxOccurs) {
    public static final int UNBOUNDED = -1;

    public FieldModel {
        namespace = namespace == null ? "" : namespace;
    }

    public boolean repeated() {
        return maxOccurs == UNBOUNDED || maxOccurs > 1;
    }

    public boolean optional() {
        return minOccurs == 0;
    }
}
