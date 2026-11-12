package dev.soapnecromancer.runtime;

import dev.soapnecromancer.runtime.model.*;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * jqwik generators for converter properties (.kiro/specs/converter, Requirement
 * 3).
 * Random models in the supported XSD subset, and canonical JSON values for any
 * model type.
 * Exported in the runtime test-jar; also copied into generated proxies (Task
 * 8).
 */
public final class ModelArbitraries {
    private ModelArbitraries() {
    }

    /** A generated model plus the root element to convert. */
    public record Generated(ServiceModel model, ElementRef root) {
    }

    // ------------------------------------------------------------ models

    private sealed interface Node permits Leaf, EnumNode, Complex {
    }

    private record Leaf(BuiltIn builtIn) implements Node {
    }

    private record EnumNode(List<String> values) implements Node {
    }

    private record Complex(List<FieldSpec> fields) implements Node {
    }

    private record FieldSpec(String name, String namespace, Occurs occurs, Node type) {
    }

    private record Occurs(int min, int max) {
    }

    private static final List<String> NAMESPACES = List.of("urn:a", "urn:b", "");

    public static Arbitrary<Generated> models() {
        Arbitrary<String> rootNs = Arbitraries.of(NAMESPACES);
        return Combinators.combine(complex(3), rootNs).as((root, ns) -> flatten((Complex) root, ns));
    }

    private static Arbitrary<Node> node(int depth) {
        Arbitrary<Node> leaf = Arbitraries.frequencyOf(
                Tuple.of(6, Arbitraries.of(BuiltIn.values()).map(b -> (Node) new Leaf(b))),
                Tuple.of(1, enumValues().map(v -> (Node) new EnumNode(v))));
        if (depth <= 0)
            return leaf;
        return Arbitraries.frequencyOf(Tuple.of(3, leaf), Tuple.of(1, complex(depth - 1)));
    }

    private static Arbitrary<Node> complex(int depth) {
        Arbitrary<String> names = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(6);
        Arbitrary<Occurs> occurs = Arbitraries.of(new Occurs(1, 1), new Occurs(0, 1), new Occurs(0, 2),
                new Occurs(0, FieldModel.UNBOUNDED), new Occurs(1, FieldModel.UNBOUNDED));
        Arbitrary<FieldSpec> field = Combinators.combine(names, Arbitraries.of(NAMESPACES), occurs, node(depth))
                .as(FieldSpec::new);
        return field.list().ofMaxSize(4).uniqueElements(FieldSpec::name).map(fs -> (Node) new Complex(fs));
    }

    private static Arbitrary<List<String>> enumValues() {
        return Arbitraries.strings().withCharRange('A', 'Z').withChars('_', '-').ofMinLength(1).ofMaxLength(5)
                .list().ofMinSize(1).ofMaxSize(4).uniqueElements();
    }

    private static Generated flatten(Complex root, String rootNs) {
        Map<String, TypeModel> types = new LinkedHashMap<>();
        int[] counter = { 0 };
        String rootId = register(root, types, counter);
        ElementRef ref = new ElementRef(rootNs, "Root", rootId);
        OperationModel op = new OperationModel("Op", "urn:op", "", ref, ref);
        return new Generated(new ServiceModel("Generated", "urn:a", "http://localhost/ws", List.of(op), types), ref);
    }

    private static String register(Node n, Map<String, TypeModel> types, int[] counter) {
        return switch (n) {
            case Leaf l -> {
                types.putIfAbsent(l.builtIn().typeId(), TypeModel.builtIn(l.builtIn()));
                yield l.builtIn().typeId();
            }
            case EnumNode e -> {
                String id = "E" + counter[0]++;
                types.put(id, TypeModel.simple(id, BuiltIn.STRING, e.values()));
                yield id;
            }
            case Complex c -> {
                String id = "T" + counter[0]++;
                types.put(id, null); // reserve for stable order
                List<FieldModel> fields = new ArrayList<>();
                for (FieldSpec f : c.fields()) {
                    fields.add(new FieldModel(f.name(), f.namespace(), register(f.type(), types, counter),
                            f.occurs().min(), f.occurs().max()));
                }
                types.put(id, TypeModel.complex(id, fields));
                yield id;
            }
        };
    }

    // ------------------------------------------------------------ canonical values

    /**
     * Canonical JSON values (see converter requirements glossary) for
     * {@code typeId}.
     */
    public static Arbitrary<Object> values(ServiceModel model, String typeId) {
        TypeModel t = model.type(typeId);
        if (t.isEnum())
            return Arbitraries.of(t.enumValues()).map(v -> (Object) v);
        if (!t.isComplex())
            return builtIn(t.base());
        if (t.fields().isEmpty())
            return Arbitraries.just(Map.of());

        List<Arbitrary<Optional<Object>>> parts = new ArrayList<>();
        for (FieldModel f : t.fields())
            parts.add(fieldValue(model, f));
        return Combinators.combine(parts).as(values -> {
            Map<String, Object> obj = new LinkedHashMap<>();
            for (int i = 0; i < values.size(); i++) {
                Optional<Object> v = values.get(i);
                if (v.isPresent())
                    obj.put(t.fields().get(i).name(), v.get());
            }
            return (Object) obj;
        });
    }

    private static Arbitrary<Optional<Object>> fieldValue(ServiceModel model, FieldModel f) {
        Arbitrary<Object> single = values(model, f.typeId());
        if (f.repeated()) {
            int max = f.maxOccurs() == FieldModel.UNBOUNDED ? 3 : f.maxOccurs();
            return single.list().ofMinSize(f.minOccurs()).ofMaxSize(max).map(l -> Optional.of((Object) l));
        }
        if (f.optional()) {
            return Arbitraries.frequencyOf(
                    Tuple.of(1, Arbitraries.just(Optional.empty())),
                    Tuple.of(2, single.map(Optional::of)));
        }
        return single.map(Optional::of);
    }

    private static final DateTimeFormatter XS_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final List<String> OFFSETS = List.of("Z", "+05:30", "-08:00", "+00:00");

    public static Arbitrary<Object> builtIn(BuiltIn b) {
        return switch (b) {
            case STRING -> xmlStrings().map(s -> (Object) s);
            case INT -> Arbitraries.integers().map(i -> (Object) i);
            case LONG -> Arbitraries.longs().map(l -> (Object) l);
            case DECIMAL -> decimals().map(d -> (Object) d);
            case BOOLEAN -> Arbitraries.of(true, false).map(v -> (Object) v);
            case DATE -> Combinators.combine(
                    Arbitraries.longs().between(LocalDate.of(1, 1, 1).toEpochDay(),
                            LocalDate.of(9999, 12, 31).toEpochDay()),
                    Arbitraries.of("", "Z", "+05:30"))
                    .as((day, zone) -> (Object) (LocalDate.ofEpochDay(day) + zone));
            case DATE_TIME -> Combinators.combine(
                    Arbitraries.longs().between(0, 4_102_444_800L), Arbitraries.of(OFFSETS))
                    .as((sec, off) -> (Object) OffsetDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneOffset.of(off))
                            .format(XS_DATE_TIME));
        };
    }

    /**
     * Strings that XML 1.0 can carry, biased toward escaping and whitespace edge
     * cases.
     */
    public static Arbitrary<String> xmlStrings() {
        return xmlStringsImpl();
    }

    /**
     * Decimals beyond double precision: up to 31 significant digits, scale 0..20,
     * trailing zeros kept (0.10 is not 0.1), plus fixed values a bank actually
     * sends.
     */
    public static Arbitrary<BigDecimal> decimals() {
        java.math.BigInteger bound = java.math.BigInteger.TEN.pow(31);
        Arbitrary<BigDecimal> random = Combinators.combine(
                Arbitraries.bigIntegers().between(bound.negate(), bound), Arbitraries.integers().between(0, 20))
                .as(BigDecimal::new);
        Arbitrary<BigDecimal> known = Arbitraries.of("0.10", "0.1", "100.50", "1234567890.123456789",
                "-0.00", "0", "9007199254740993", "0.30000000000000001").map(BigDecimal::new);
        return Arbitraries.frequencyOf(Tuple.of(4, random), Tuple.of(1, known));
    }

    private static Arbitrary<String> xmlStringsImpl() {
        return Arbitraries.oneOf(
                Arbitraries.strings().withCharRange(' ', '~').withChars('\t', '\n', '\r', '\u00e9', '\u4e2d', '\u00a0')
                        .ofMaxLength(12),
                Arbitraries.of("", " leading", "trailing ", "a&b<c>d", "]]>", "\r\n", "\r", "\uD83D\uDE00 emoji",
                        "\uD834\uDD1E", "\"quoted\" 'single'", "&amp; literal", "  "));
    }
}
