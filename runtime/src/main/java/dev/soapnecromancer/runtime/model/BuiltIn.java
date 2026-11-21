package dev.soapnecromancer.runtime.model;

/** XSD built-in types in the supported core subset. */
public enum BuiltIn {
    STRING("xs:string"),
    INT("xs:int"),
    LONG("xs:long"),
    /**
     * xs:integer and the families derived from it with no fixed width: arbitrary
     * precision.
     */
    INTEGER("xs:integer"),
    DECIMAL("xs:decimal"),
    BOOLEAN("xs:boolean"),
    DATE("xs:date"),
    DATE_TIME("xs:dateTime");

    private final String typeId;

    BuiltIn(String typeId) {
        this.typeId = typeId;
    }

    /** Type id used for this built-in in {@link ServiceModel#types()}. */
    public String typeId() {
        return typeId;
    }
}
