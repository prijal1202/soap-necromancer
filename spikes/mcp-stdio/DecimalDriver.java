import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Spike client: java DecimalDriver.java target/mcp-stdio-0.0.1.jar */
public class DecimalDriver {
    public static void main(String[] args) throws Exception {
        Process p = new ProcessBuilder("java", "-cp", args[0], "spike.DecimalMcpServer", args.length > 1 ? args[1] : "configured")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var out = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

        send(out, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"driver\",\"version\":\"1\"}}}");
        in.readLine();
        send(out, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        int id = 2;
        for (String amount : new String[] {"0.10", "1234567890.123456789", "2500.00", "100", "\"12.50\""}) {
            send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id++ + ",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"amount\":" + amount + "}}}");
            System.out.println(amount + " -> " + in.readLine());
        }
        out.close();
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
    }

    static void send(BufferedWriter w, String json) throws IOException {
        w.write(json);
        w.write("\n");
        w.flush();
    }
}
