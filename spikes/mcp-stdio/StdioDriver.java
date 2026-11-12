import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Spike client: launches the server jar the way Kiro does (java -jar, stdio) and speaks raw
 * newline-delimited JSON-RPC. Run: java StdioDriver.java target/mcp-stdio-0.0.1.jar
 */
public class StdioDriver {
    public static void main(String[] args) throws Exception {
        Process p = new ProcessBuilder("java", "-jar", args[0]).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var out = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

        send(out, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"driver\",\"version\":\"1\"}}}");
        System.out.println("initialize -> " + in.readLine());
        send(out, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(out, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        System.out.println("tools/list -> " + in.readLine());
        send(out, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"hello\",\"arguments\":{\"name\":\"Kiro\"}}}");
        System.out.println("tools/call -> " + in.readLine());

        out.close();
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
    }

    static void send(BufferedWriter w, String json) throws IOException {
        w.write(json);
        w.write("\n");
        w.flush();
    }
}
