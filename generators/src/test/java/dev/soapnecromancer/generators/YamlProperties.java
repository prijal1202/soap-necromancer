package dev.soapnecromancer.generators;

import net.jqwik.api.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-written YAML writer must produce what an independent reader (SnakeYAML) reads back
 * exactly: keys that look like booleans or numbers, control characters, quotes, unicode, nesting.
 */
@Tag("fast")
class YamlProperties {

    @Property
    void independentReaderReadsBackTheSameDocument(@ForAll("documents") Map<String, Object> doc) {
        String yaml = Yaml.write(doc);
        Object back = new org.yaml.snakeyaml.Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        assertThat(back).as(yaml).isEqualTo(doc.isEmpty() ? Map.of() : doc);
    }

    @Provide
    Arbitrary<Map<String, Object>> documents() {
        return Arbitraries.maps(keys(), values()).ofMaxSize(4);
    }

    private Arbitrary<String> keys() {
        return Arbitraries.oneOf(
                Arbitraries.of("type", "$ref", "200", "/GetBalance", "application/json", "yes", "No", "on",
                        "null", "true", "y", "~", "<<", "-x", "a: b", "#c", "", " lead", "key-1", "x.y"),
                text());
    }

    private Arbitrary<Object> values() {
        Arbitrary<Object> leaf = Arbitraries.oneOf(
                text().map(s -> (Object) s),
                Arbitraries.integers().map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b));
        return Arbitraries.recursive(() -> leaf, inner -> Arbitraries.oneOf(
                inner.list().ofMaxSize(3).map(l -> (Object) l),
                Arbitraries.maps(keys(), inner).ofMaxSize(3).map(m -> (Object) m)), 0, 3);
    }

    /** Any code point except lone surrogates (model strings come from XML and cannot contain them). */
    private Arbitrary<String> text() {
        return Arbitraries.oneOf(
                Arbitraries.strings().withCharRange('\u0000', '\uD7FF').withCharRange('\uE000', '\uFFFF').ofMaxLength(10),
                Arbitraries.of("", "\"", "\\", "'", "a\nb", "\r\n", "\t", "\u0085", "\u2028", "\uFEFF", "\uD83D\uDE00",
                        "- item", "key: value", "# comment", "&anchor", "*alias", "!tag", "%", "@", "`", "{}", "[]",
                        "123", "1e3", "0x1F", ".inf", "~", "null", "true"));
    }
}
