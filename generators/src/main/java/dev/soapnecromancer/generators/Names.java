package dev.soapnecromancer.generators;

import javax.lang.model.SourceVersion;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Sanitizes model names into the identifiers each generated artifact allows.
 * Every result is
 * bounded in length (file names over 255 bytes, or paths over Windows'
 * MAX_PATH, fail to extract)
 * and never a Windows device name ({@code con}, {@code nul}, ...), which
 * Windows cannot create.
 */
public final class Names {
    /**
     * Length of any name derived from the WSDL; longer ones are cut and suffixed
     * with a hash.
     */
    static final int MAX_NAME = 64;
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])");

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
        return cap(s.isEmpty() ? "_" : s);
    }

    /**
     * Java package for generated code: {@code generated.<lowercase alphanumerics>}.
     * Generators Requirement 5.4.
     */
    public static String javaPackage(String serviceName) {
        String s = cap(serviceName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        if (s.isEmpty())
            s = "service";
        if (!Character.isJavaIdentifierStart(s.charAt(0)))
            s = "s" + s;
        if (SourceVersion.isKeyword(s) || WINDOWS_DEVICE.matcher(s).matches())
            s = s + "_";
        return "generated." + s;
    }

    /**
     * Maven artifactId: kebab-case of the service name, e.g. LegacyBankService ->
     * legacy-bank-service.
     */
    public static String artifactId(String serviceName, String suffix) {
        String s = serviceName.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return cap(s.isEmpty() ? "service" : s) + "-" + suffix;
    }

    /** Java class name from an operation name, e.g. Get-Balance -> Get_Balance. */
    public static String javaClass(String name, String suffix) {
        String s = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0)))
            s = "Op" + s;
        s = cap(s);
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
        for (String id : ids)
            out.put(id, uniquify(component(id), "_", used, false));
        return out;
    }

    /**
     * {@code base}, or {@code base + separator + n} for the smallest n >= 2 not yet
     * in {@code used};
     * the result is added to {@code used}. With {@code ignoreCase}, names differing
     * only in case
     * count as taken (their files would collide on Windows and macOS); {@code used}
     * then holds
     * lower-case names.
     */
    static String uniquify(String base, String separator, Set<String> used, boolean ignoreCase) {
        String name = base;
        for (int i = 2; !used.add(ignoreCase ? name.toLowerCase(Locale.ROOT) : name); i++)
            name = base + separator + i;
        return name;
    }

    /**
     * Names longer than {@link #MAX_NAME} keep a prefix and a short hash, so they
     * stay distinct.
     */
    static String cap(String s) {
        if (s.length() <= MAX_NAME)
            return s;
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        String hash = String.format("%08x", crc.getValue());
        return s.substring(0, MAX_NAME - hash.length() - 1) + "_" + hash;
    }
}
