package dev.soapnecromancer.generators;

import dev.soapnecromancer.core.WsdlParser;
import dev.soapnecromancer.runtime.model.ServiceModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Generators spec, Requirement 3 (generated tests). Compilation is covered by ProxyGeneratorTest. */
@Tag("fast")
class TestGeneratorTest {
    private static final ServiceModel BANK = new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl")).model();
    private static final String DIR = "proxy/src/test/java/generated/legacybankservice/";

    private static GeneratedFiles generate(ServiceModel model) {
        GeneratedFiles files = new GeneratedFiles();
        new TestGenerator().generate(model, files);
        return files;
    }

    @Test
    void req3_1_req3_5_oneContractTestPerOperationPlusSupportAndProperties() {
        List<String> paths = generate(BANK).files().keySet().stream().sorted().toList();
        assertThat(paths).containsExactly(
                DIR + "ContractTestSupport.java", DIR + "GetBalanceContractTest.java", DIR + "GetStatementContractTest.java",
                DIR + "RoundTripProperties.java", DIR + "StubSoapServer.java", DIR + "TransferContractTest.java");
    }

    @Test
    void req3_2_req3_3_eachContractTestCoversSuccessUnknownFieldAndFaults() {
        String getBalance = generate(BANK).text(DIR + "GetBalanceContractTest.java");
        assertThat(getBalance)
                .contains("private static final String OPERATION = \"GetBalance\";")
                .contains("void validRequestIsSentAsSoapAndTheResponseComesBackAsJson()")
                .contains("void unknownFieldIsRejectedWithItsPathAndNothingIsSent()")
                .contains("void soapClientFaultIs400WithTheFault()")
                .contains("void soapServerFaultIs502()")
                .contains("void nonSoapErrorPageIs502AndIsNotLeaked()");
    }

    @Test
    void req6_3_decimalTestOnlyWhereTheInputHasATopLevelDecimal() {
        GeneratedFiles files = generate(BANK);
        assertThat(files.text(DIR + "TransferContractTest.java"))
                .contains("void decimalsReachTheBackendExactly()")
                .contains("request.put(\"amount\", new BigDecimal(amount));");
        assertThat(files.text(DIR + "GetBalanceContractTest.java")).doesNotContain("decimalsReachTheBackendExactly");
        assertThat(files.text(DIR + "GetStatementContractTest.java")).doesNotContain("decimalsReachTheBackendExactly");
    }

    @Test
    void req3_5_roundTripPropertiesAreTaggedFastAndLoadModelJson() {
        String props = generate(BANK).text(DIR + "RoundTripProperties.java");
        assertThat(props).contains("@Tag(\"fast\")").contains("getResourceAsStream(\"/model.json\")")
                .contains("void decimalsSurviveExactly(");
    }

    @Test
    void contractTestNamesFollowControllerNamesIncludingUniquenessSuffix() {
        List<String> names = ProxyGenerator.controllers(ProxyGeneratorTest.hostile()).stream()
                .map(TestGenerator::contractTestName).toList();
        assertThat(names).containsExactly("Get_Balance_v2ContractTest", "GetBalanceContractTest",
                "GetBalanceContractTest2", "HealthContractTest2");
    }

    @Test
    void generatorOutputIncludesTheTests() {
        GeneratedFiles all = new Generator().generate(new WsdlParser().parse(OpenApiGeneratorTest.resource("bank.wsdl")));
        assertThat(all.files()).containsKey(DIR + "TransferContractTest.java");
    }
}
