package dev.soapnecromancer.runtime.model;

import java.util.List;

/**
 * A complex type (ordered fields) or a simple type (built-in base, optional
 * enum values).
 * One record with a {@link Kind} keeps runtime free of JSON annotations and
 * keeps templates simple.
 */
public record TypeModel(String id, Kind kind, BuiltIn base, List<String> enumValues, List<FieldModel> fields) {

    public enum Kind {
        COMPLEX, SIMPLE
    }

    public TypeModel {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static TypeModel complex(String id, List<FieldModel> fields) {
        return new TypeModel(id, Kind.COMPLEX, null, List.of(), fields);
    }

    public static TypeModel simple(String id, BuiltIn base, List<String> enumValues) {
        return new TypeModel(id, Kind.SIMPLE, base, enumValues, List.of());
    }

    public static TypeModel builtIn(BuiltIn b) {
        return simple(b.typeId(), b, List.of());
    }

    public boolean isComplex() {
        return kind == Kind.COMPLEX;
    }

    public boolean isEnum() {
        return kind == Kind.SIMPLE && !enumValues.isEmpty();
    }

    /**
     * The built-in a simple value is read and written as; string when the model
     * names none.
     */
    public BuiltIn baseOrString() {
        return base == null ? BuiltIn.STRING : base;
    }
}
