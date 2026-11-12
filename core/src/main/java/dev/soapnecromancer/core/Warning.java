package dev.soapnecromancer.core;

/** An unsupported or ignored WSDL/XSD feature, reported to the UI via generator-report.json. */
public record Warning(String code, String message, String location) {}
