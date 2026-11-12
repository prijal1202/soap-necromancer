import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Spike: bind 0.0.0.0:$PORT like Render requires, and report what the JVM sees inside the container limit. */
public class Hello {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", ex -> {
            Runtime rt = Runtime.getRuntime();
            String body = "{\"port\":" + port
                    + ",\"maxHeapMb\":" + rt.maxMemory() / (1024 * 1024)
                    + ",\"cpus\":" + rt.availableProcessors() + "}\n";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        System.out.println("listening on 0.0.0.0:" + port);
    }
}
