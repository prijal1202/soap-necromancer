import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Spike (Task 10): cold start, latency and memory of the packaged generator web app, with the JVM
 * sized as if it ran in a 512 MB single-CPU container (MaxRAM=512m, ActiveProcessorCount=1).
 * Not a real container: CPU is not throttled. The Docker check stays in Task 12.
 *
 * Run from the repo root after "mvn -pl web -am package -DskipTests":
 *   java spikes/render-web/Measure.java
 * Writes measure.log; the last line is DONE when complete.
 */
public class Measure {
    static final int PORT = 10010;
    static final String BASE = "http://127.0.0.1:" + PORT;
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final List<String> OUT = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path log = Path.of("measure.log");
        Files.deleteIfExists(log);
        // A leftover instance on the port would answer instead of the JVM being measured (happened once).
        try (java.net.ServerSocket probe = new java.net.ServerSocket(PORT)) {
            // free
        } catch (IOException busy) {
            Files.write(log, List.of("ABORT port " + PORT + " is already in use", "DONE"), StandardCharsets.UTF_8);
            return;
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java,
                "-XX:MaxRAM=512m", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-Xss512k",
                "-XX:ActiveProcessorCount=1", "-jar", "web/target/soap-necromancer.jar")
                .redirectErrorStream(true).redirectOutput(new File("measure-app.log"));
        pb.environment().put("PORT", String.valueOf(PORT));
        long t0 = System.nanoTime();
        Process app = pb.start();
        try {
            note("jvm_flags=MaxRAM=512m MaxRAMPercentage=70 SerialGC Xss512k ActiveProcessorCount=1");
            boolean up = false;
            while (!up && System.nanoTime() - t0 < 180_000_000_000L) {
                try {
                    up = get("/api/health").statusCode() == 200;
                } catch (IOException notYet) {
                    Thread.sleep(200);
                }
            }
            note("healthy=" + up + " after_ms=" + (System.nanoTime() - t0) / 1_000_000);
            note("rss_after_start_kb=" + rssKb(app.pid()));

            String wsdl = get("/api/sample-wsdl").body();
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                long s = System.nanoTime();
                HttpResponse<byte[]> r = post(wsdl);
                times.add((System.nanoTime() - s) / 1_000_000);
                if (i == 0) note("first_generate status=" + r.statusCode() + " zip_bytes=" + r.body().length);
            }
            note("generate_ms=" + times);

            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Callable<Integer> c = () -> post(wsdl).statusCode();
                fs.add(pool.submit(c));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) codes.add(f.get());
            pool.shutdown();
            note("concurrent8_statuses=" + codes);
            note("rss_after_load_kb=" + rssKb(app.pid()));
            note("app_alive=" + app.isAlive() + " pid=" + app.pid());
            for (String l : Files.readAllLines(Path.of("measure-app.log"), StandardCharsets.UTF_8)) {
                if (l.contains("Started NecromancerApplication")) note("app_log: " + l.substring(l.indexOf("Started")));
            }
        } finally {
            app.destroy();
            app.waitFor();
            note("DONE");
            Files.write(log, OUT, StandardCharsets.UTF_8);
        }
    }

    static void note(String s) {
        OUT.add(s);
    }

    static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<byte[]> post(String wsdl) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/api/generate")).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString(wsdl)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Resident set size of a process in KB (Windows tasklist; Linux /proc). */
    static String rssKb(long pid) throws IOException, InterruptedException {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Process p = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid, "/fo", "csv", "/nh").start();
            String line = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            String[] cols = line.split("\",\"");
            return cols.length >= 5 ? cols[4].replaceAll("[^0-9]", "") : "unknown(" + line + ")";
        }
        for (String l : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
            if (l.startsWith("VmRSS:")) return l.replaceAll("[^0-9]", "");
        }
        return "unknown";
    }
}
