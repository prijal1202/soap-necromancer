package dev.soapnecromancer.core;

import dev.soapnecromancer.runtime.model.SoapVersion;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Port;
import javax.wsdl.extensions.soap.SOAPAddress;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.extensions.soap.SOAPBody;
import javax.wsdl.extensions.soap.SOAPHeader;
import javax.wsdl.extensions.soap.SOAPOperation;
import javax.wsdl.extensions.soap12.SOAP12Address;
import javax.wsdl.extensions.soap12.SOAP12Binding;
import javax.wsdl.extensions.soap12.SOAP12Body;
import javax.wsdl.extensions.soap12.SOAP12Header;
import javax.wsdl.extensions.soap12.SOAP12Operation;
import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One SOAP binding, with its version. WSDL4J models {@code soap:} and {@code soap12:} extensions
 * as unrelated types with the same accessors; this reads the ones of the binding's version, so the
 * parser's operation checks exist once (parser Requirement 2.3).
 */
record SoapBinding(SoapVersion version, Binding binding) {

    /** The {@code soap:operation} or {@code soap12:operation} of a binding operation. */
    record OperationInfo(String action, String style) {
    }

    /** A {@code soap:header} / {@code soap12:header}: which part of which message it carries. */
    record Header(QName message, String part) {
    }

    /** The binding's version, or empty when it is not a SOAP binding (HTTP, MIME, undefined). */
    static Optional<SoapBinding> of(Binding b) {
        if (b == null || b.isUndefined())
            return Optional.empty();
        // A binding carrying both kinds is malformed; SOAP 1.1 wins, like the port preference.
        if (first(b.getExtensibilityElements(), SOAPBinding.class) != null)
            return Optional.of(new SoapBinding(SoapVersion.SOAP_11, b));
        if (first(b.getExtensibilityElements(), SOAP12Binding.class) != null)
            return Optional.of(new SoapBinding(SoapVersion.SOAP_12, b));
        return Optional.empty();
    }

    /** {@code style} of the binding element, "document" when absent. */
    String style() {
        String style = switch (version) {
            case SOAP_11 -> styleOf(first(binding.getExtensibilityElements(), SOAPBinding.class));
            case SOAP_12 -> styleOf(first(binding.getExtensibilityElements(), SOAP12Binding.class));
        };
        return style == null ? "document" : style;
    }

    String address(Port port) {
        List<?> ext = port.getExtensibilityElements();
        return switch (version) {
            case SOAP_11 -> {
                SOAPAddress a = first(ext, SOAPAddress.class);
                yield a == null ? null : a.getLocationURI();
            }
            case SOAP_12 -> {
                SOAP12Address a = first(ext, SOAP12Address.class);
                yield a == null ? null : a.getLocationURI();
            }
        };
    }

    /** soapAction ("" when absent) and the operation's own style (null when it does not set one). */
    OperationInfo operation(BindingOperation bop) {
        List<?> ext = bop.getExtensibilityElements();
        return switch (version) {
            case SOAP_11 -> {
                SOAPOperation op = first(ext, SOAPOperation.class);
                yield op == null ? new OperationInfo("", null) : new OperationInfo(nonNull(op.getSoapActionURI()), op.getStyle());
            }
            case SOAP_12 -> {
                SOAP12Operation op = first(ext, SOAP12Operation.class);
                yield op == null ? new OperationInfo("", null) : new OperationInfo(nonNull(op.getSoapActionURI()), op.getStyle());
            }
        };
    }

    /** {@code use} of the input or output body, null when absent. */
    String bodyUse(List<?> ext) {
        return switch (version) {
            case SOAP_11 -> {
                SOAPBody b = first(ext, SOAPBody.class);
                yield b == null ? null : b.getUse();
            }
            case SOAP_12 -> {
                SOAP12Body b = first(ext, SOAP12Body.class);
                yield b == null ? null : b.getUse();
            }
        };
    }

    /** The body's {@code parts=} list, or null when the attribute is absent. */
    List<?> bodyParts(List<?> ext) {
        return switch (version) {
            case SOAP_11 -> {
                SOAPBody b = first(ext, SOAPBody.class);
                yield b == null ? null : b.getParts();
            }
            case SOAP_12 -> {
                SOAP12Body b = first(ext, SOAP12Body.class);
                yield b == null ? null : b.getParts();
            }
        };
    }

    List<Header> headers(List<?> ext) {
        List<Header> out = new ArrayList<>();
        for (Object o : ext == null ? List.of() : ext) {
            switch (version) {
                case SOAP_11 -> {
                    if (o instanceof SOAPHeader h)
                        out.add(new Header(h.getMessage(), h.getPart()));
                }
                case SOAP_12 -> {
                    if (o instanceof SOAP12Header h)
                        out.add(new Header(h.getMessage(), h.getPart()));
                }
            }
        }
        return out;
    }

    private static String styleOf(SOAPBinding b) {
        return b == null ? null : b.getStyle();
    }

    private static String styleOf(SOAP12Binding b) {
        return b == null ? null : b.getStyle();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    static <T> T first(List<?> elements, Class<T> type) {
        if (elements == null)
            return null;
        for (Object o : elements) {
            if (type.isInstance(o))
                return type.cast(o);
        }
        return null;
    }
}
