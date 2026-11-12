package dev.soapnecromancer.generators;

import java.util.ArrayList;
import java.util.List;

/**
 * The runtime module's sources, bundled into the generator jar under {@code runtime-src/} by the
 * build (see generators/pom.xml). Copied verbatim into generated projects.
 */
final class RuntimeSources {
    private static final String BASE = "runtime-src/";

    private RuntimeSources() {}

    /** Index entries such as {@code main/dev/soapnecromancer/runtime/SoapClient.java}. */
    static List<String> index() {
        List<String> out = new ArrayList<>();
        for (String line : Templates.resource(BASE + "index.txt").split("\n")) {
            String l = line.strip();
            if (!l.isEmpty()) out.add(l);
        }
        if (out.isEmpty()) throw new IllegalStateException("runtime-src/index.txt is empty");
        return out;
    }

    /** main/** -> {root}src/main/java/**, test/** -> {root}src/test/java/**. */
    static void copyInto(GeneratedFiles out, String root) {
        for (String entry : index()) {
            String scope = entry.substring(0, entry.indexOf('/'));
            String rel = entry.substring(scope.length() + 1);
            out.add(root + "src/" + scope + "/java/" + rel, Templates.resource(BASE + entry));
        }
    }
}
