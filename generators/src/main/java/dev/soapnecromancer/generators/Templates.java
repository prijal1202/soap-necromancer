package dev.soapnecromancer.generators;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JMustache templates under {@code templates/} on the classpath. HTML escaping is off: callers pass
 * values already escaped for the target language (see {@link Escapes}). A missing variable fails.
 */
final class Templates {
    private static final Mustache.Compiler COMPILER = Mustache.compiler().escapeHTML(false);
    private static final Map<String, Template> CACHE = new ConcurrentHashMap<>();

    private Templates() {}

    static String render(String name, Object context) {
        return CACHE.computeIfAbsent(name, Templates::compile).execute(context);
    }

    private static Template compile(String name) {
        return COMPILER.compile(resource("templates/" + name + ".mustache"));
    }

    /** Classpath text with LF line endings, so output is identical on every OS. */
    static String resource(String path) {
        try (InputStream in = Templates.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing generator resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
