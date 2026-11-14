package dev.soapnecromancer.generators;

import dev.soapnecromancer.runtime.model.BuiltIn;
import dev.soapnecromancer.runtime.model.FieldModel;
import dev.soapnecromancer.runtime.model.OperationModel;
import dev.soapnecromancer.runtime.model.ServiceModel;
import dev.soapnecromancer.runtime.model.SoapVersion;
import dev.soapnecromancer.runtime.model.TypeModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generator: the generated proxy's tests (generators spec, Requirement 3). REST
 * Assured contract
 * tests per operation against a stub SOAP server, plus jqwik round-trip
 * properties over model.json.
 * The jqwik helpers they use (ModelArbitraries, XmlRenderer,
 * RoundTripAssertions) arrive with the
 * copied runtime sources.
 */
public final class TestGenerator {

    public void generate(ServiceModel model, GeneratedFiles out) {
        String pkg = Names.javaPackage(model.name());
        String dir = ProxyGenerator.ROOT + "src/test/java/" + pkg.replace('.', '/') + "/";

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("package", pkg);
        // The stub speaks the model's SOAP version (generators Requirement 3.3).
        SoapVersion version = model.soapVersion();
        ctx.put("soap12", version == SoapVersion.SOAP_12);
        ctx.put("soapVersionLabel", version.label());
        ctx.put("envelopeNsJava", Escapes.javaString(version.envelopeNamespace()));
        ctx.put("mediaTypeJava", Escapes.javaString(version.mediaType()));
        ctx.put("clientFaultName", Faults.client(version));
        ctx.put("serverFaultName", Faults.server(version));
        for (String cls : List.of("StubSoapServer", "ContractTestSupport", "RoundTripProperties")) {
            out.add(dir + cls + ".java", Templates.render("proxy/test/" + cls + ".java", ctx));
        }

        for (ProxyGenerator.Controller c : ProxyGenerator.controllers(model)) {
            OperationModel op = c.operation();
            String className = contractTestName(c);
            Map<String, Object> opCtx = new HashMap<>(ctx);
            opCtx.put("className", className);
            opCtx.put("nameJava", Escapes.javaString(op.name()));
            opCtx.put("nameDoc", Escapes.javaComment(op.name()));
            opCtx.put("pathJava", Escapes.javaString("/" + op.name()));
            opCtx.put("pathDoc", Escapes.javaComment("/" + op.name()));
            opCtx.put("complexInput", model.type(op.input().typeId()).isComplex());
            Optional<String> decimal = topLevelDecimalField(model, op);
            opCtx.put("hasDecimalField", decimal.isPresent());
            opCtx.put("decimalFieldJava", Escapes.javaString(decimal.orElse("")));
            out.add(dir + className + ".java", Templates.render("proxy/test/OperationContractTest.java", opCtx));
        }
    }

    /**
     * TransferController -> TransferContractTest. Unique because controller names
     * are.
     */
    static String contractTestName(ProxyGenerator.Controller c) {
        String n = c.className();
        int i = n.lastIndexOf("Controller"); // names end in Controller plus an optional uniqueness number
        return n.substring(0, i) + "ContractTest" + n.substring(i + "Controller".length());
    }

    /**
     * First single-valued xs:decimal field directly in the input type (e.g.
     * Transfer.amount).
     */
    static Optional<String> topLevelDecimalField(ServiceModel model, OperationModel op) {
        TypeModel in = model.type(op.input().typeId());
        if (!in.isComplex())
            return Optional.empty();
        for (FieldModel f : in.fields()) {
            TypeModel t = model.type(f.typeId());
            if (!f.repeated() && !t.isComplex() && !t.isEnum() && t.base() == BuiltIn.DECIMAL)
                return Optional.of(f.name());
        }
        return Optional.empty();
    }
}
