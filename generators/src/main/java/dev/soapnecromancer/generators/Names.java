package dev.soapnecromancer.generators;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sanitizes model names into the identifiers each generated artifact allows.
 */
public final class Names {
    private Names() {
    }

    /**
     * OpenAPI component / JSON Schema $defs key: {@code [A-Za-z0-9._-]+}
     * (generators Requirement 1.7).
     */
    public static String component(String typeId) {
        String s = typeId.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "_" : s;
    }

    /** MCP tool name: {@code [A-Za-z0-9_-]+} (generators Requirement 4.2). */
    public static String tool(String operationName) {
        String s = operationName.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isEmpty() ? "_" : s;
    }

    /**
     * Java package for generated code: {@code generated.<lowercase alphanumerics>}.
     * Generators Requirement 5.4.
     */
    public static String javaPackage(String serviceName) {
        String s = serviceName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (s.isEmpty())
            s = "service";
        if (!Character.isJavaIdentifierStart(s.charAt(0)))
            s = "s" + s;
        if (javax.lang.model.SourceVersion.isKeyword(s))
            s = s + "_";
        return "generated." + s;
    }

    /**
     * Maven artifactId: kebab-case of the service name, e.g. LegacyBankService ->
     * legacy-bank-service.
     */
    public static String artifactId(String serviceName, String suffix) {
        String s = serviceName.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return (s.isEmpty() ? "service" : s) + "-" + suffix;
    }

    /** Java class name from an operation name, e.g. Get-Balance -> Get_Balance. */
    public static String javaClass(String name, String suffix) {
        String s = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0)))
            s = "Op" + s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + suffix;
    }

    /**
     * Assigns a unique sanitized name to each id, in order. {@code reserved} names
     * (e.g. "Problem")
     * are never handed out, so a WSDL type cannot collide with a generator-owned
     * schema.
     */
    public static Map<String, String> unique(List<String> ids, Collection<String> reserved) {
        Set<String> used = new HashSet<>(reserved);
        Map<String, String> out = new LinkedHashMap<>();
        for (String id : ids) {
            String base = component(id);
            String name = base;
            for (int i = 2; !used.add(name); i++)
                name = base + "_" + i;
            out.put(id, name);
        }
        return out;
    }
}
