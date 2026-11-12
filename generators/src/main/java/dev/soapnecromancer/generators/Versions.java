package dev.soapnecromancer.generators;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Versions written into generated projects, from the Maven-filtered generator.properties. */
public final class Versions {
    private static final Properties P = load();

    public static final String GENERATOR = get("generator.version");
    public static final String SPRING_BOOT = get("spring-boot.version");
    public static final String JQWIK = get("jqwik.version");
    public static final String MCP_SDK = get("mcp-sdk.version");

    private Versions() {}

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = Versions.class.getResourceAsStream("/generator.properties")) {
            if (in == null) throw new IllegalStateException("generator.properties missing from the generator jar");
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private static String get(String key) {
        String v = P.getProperty(key);
        if (v == null || v.isBlank() || v.startsWith("${")) {
            throw new IllegalStateException("generator.properties: " + key + " was not filtered by Maven");
        }
        return v;
    }
}
