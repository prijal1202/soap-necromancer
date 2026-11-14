package dev.soapnecromancer.generators;

import dev.soapnecromancer.core.ModelJson;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generator: canonical model -> {@code proxy/**}, a standalone Spring Boot 3
 * project (the generated
 * proxy). Generators spec, Requirement 2.
 *
 * <p>
 * The model records reach the generated proxy only as copied runtime sources;
 * this class never
 * emits model, DTO or JAXB classes (Requirement 2.2). The model itself ships as
 * {@code model.json}.
 */
public final class ProxyGenerator {
    static final String ROOT = "proxy/";
    static final List<String> FIXED_CLASSES = List.of("ProxyApplication", "SoapProperties", "ModelConfig",
            "SoapInvoker", "ProblemHandler", "HealthController");

    record Controller(OperationModel operation, String className) {
    }

    public void generate(ServiceModel model, GeneratedFiles out) {
        String pkg = Names.javaPackage(model.name());
        String pkgDir = ROOT + "src/main/java/" + pkg.replace('.', '/') + "/";

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("package", pkg);
        ctx.put("serviceNameDoc", Escapes.javaComment(model.name()));
        ctx.put("serviceNameXmlComment", Escapes.xmlComment(model.name()));
        ctx.put("serviceNamePom", Escapes.pomText(model.name()));
        ctx.put("artifactId", artifactId(model));
        ctx.put("generatorVersion", Versions.GENERATOR);
        ctx.put("springBootVersion", Versions.SPRING_BOOT);
        ctx.put("jqwikVersion", Versions.JQWIK);
        String endpoint = defaultEndpoint(model);
        ctx.put("endpointProperty", endpoint == null ? "" : endpoint);

        out.add(ROOT + "pom.xml", Templates.render("proxy/pom.xml", ctx));
        out.add(ROOT + "src/main/resources/application.properties",
                Templates.render("proxy/application.properties", ctx));
        out.add(ROOT + "src/main/resources/model.json", ModelJson.write(model));
        for (String cls : FIXED_CLASSES) {
            out.add(pkgDir + cls + ".java", Templates.render("proxy/" + cls + ".java", ctx));
        }

        for (Controller c : controllers(model)) {
            OperationModel op = c.operation();
            Map<String, Object> opCtx = new HashMap<>(ctx);
            opCtx.put("className", c.className());
            opCtx.put("pathJava", Escapes.javaString("/" + op.name()));
            opCtx.put("pathDoc", Escapes.javaComment("/" + op.name()));
            opCtx.put("nameJava", Escapes.javaString(op.name()));
            opCtx.put("nameDoc", Escapes.javaComment(op.name()));
            opCtx.put("docDoc", Escapes.javaComment(op.documentation()));
            out.add(pkgDir + c.className() + ".java", Templates.render("proxy/OperationController.java", opCtx));
        }

        RuntimeSources.copyInto(out, ROOT);
    }

    /**
     * The WSDL's soap:address if it is a plain http(s) URI that can sit inside
     * {@code ${SOAP_ENDPOINT:...}} unescaped; otherwise null, and SOAP_ENDPOINT
     * must be set.
     */
    public static String defaultEndpoint(ServiceModel model) {
        String url = model.endpointUrl();
        if (url == null || url.length() > 2048 || !url.matches("https?://[A-Za-z0-9._~:/?#\\[\\]@!&'()*+,;=%-]+"))
            return null;
        try {
            java.net.URI uri = java.net.URI.create(url);
            // Credentials in the address (user:pass@host) must not be copied into generated
            // files.
            return uri.getHost() == null || uri.getRawUserInfo() != null ? null : url;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String artifactId(ServiceModel model) {
        return Names.artifactId(model.name(), "proxy");
    }

    /**
     * One controller class per operation. Unique case-insensitively (file names
     * must not collide on
     * Windows/macOS) and never equal to a fixed class of the generated proxy.
     */
    static List<Controller> controllers(ServiceModel model) {
        Set<String> used = new HashSet<>();
        for (String fixed : FIXED_CLASSES)
            used.add(fixed.toLowerCase(Locale.ROOT));
        List<Controller> out = new ArrayList<>();
        for (OperationModel op : model.operations())
            out.add(new Controller(op, Names.uniquify(Names.javaClass(op.name(), "Controller"), "", used, true)));
        return out;
    }
}
