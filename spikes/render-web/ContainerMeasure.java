import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Spike (Task 10/12): a Docker image under Render's free limits (512 MB, no swap, 0.1 CPU by default),
 * PORT set like Render. Memory comes from the container's cgroup.
 *
 *   java spikes/render-web/ContainerMeasure.java <image> [cpus] [label]
 * Writes container-measure-<label>.log line by line (a stuck docker call cannot hide earlier results);
 * the last line is DONE.
 */
public class ContainerMeasure {
    static final int PORT = 10010;
    static final String NAME = "sn-measure";
    static final String BASE = "http://127.0.0.1:" + PORT;
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    static Path log;

    public static void main(String[] args) throws Exception {
        String image = args.length > 0 ? args[0] : "soap-necromancer";
        String cpus = args.length > 1 ? args[1] : "0.1";
        String label = args.length > 2 ? args[2] : image + "-" + cpus;
        // Optional 4th arg replaces the image's JAVA_TOOL_OPTIONS (keep the GC the same, or a CDS archive is rejected).
        String javaOpts = args.length > 3 ? args[3] : null;
        log = Path.of("container-measure-" + label + ".log");
        Files.write(log, List.of());
        try (java.net.ServerSocket probe = new java.net.ServerSocket(PORT)) {
            // port free
        } catch (IOException busy) {
            note("ABORT port " + PORT + " busy");
            note("DONE");
            return;
        }
        run(30, "docker", "stop", "-t", "5", NAME);
        run(30, "docker", "rm", NAME);
        long t0 = System.nanoTime();
        // Detached: an attached `docker run` client dropped its connection mid-run on Docker Desktop
        // (the app kept running), which used to end the measurement early.
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", NAME,
                "--memory", "512m", "--memory-swap", "512m", "--cpus", cpus,
                "-e", "PORT=" + PORT, "-p", PORT + ":" + PORT));
        if (javaOpts != null) cmd.addAll(List.of("-e", "JAVA_TOOL_OPTIONS=" + javaOpts));
        cmd.add(image);
        String id = run(60, cmd.toArray(String[]::new)).trim();
        try {
            note("image=" + image + " limits: memory=512m swap=none cpus=" + cpus
                    + (javaOpts != null ? " JAVA_TOOL_OPTIONS=" + javaOpts : "") + " container=" + id);
            boolean up = false;
            while (!up && System.nanoTime() - t0 < 300_000_000_000L && running()) {
                try {
                    up = get("/api/health").statusCode() == 200;
                } catch (IOException notYet) {
                    Thread.sleep(250);
                }
            }
            note("healthy=" + up + " after_ms=" + (System.nanoTime() - t0) / 1_000_000 + " (from docker run)");
            if (!up) return;
            note("app_started: " + startedLine(label));
            note("cgroup_memory_after_start_mb=" + mb(run(20, "docker", "exec", NAME, "cat", "/sys/fs/cgroup/memory.current")));

            long s = System.nanoTime();
            String wsdl = get("/api/sample-wsdl").body();
            note("first_sample_wsdl_ms=" + (System.nanoTime() - s) / 1_000_000);
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                s = System.nanoTime();
                HttpResponse<byte[]> r = post(wsdl);
                times.add((System.nanoTime() - s) / 1_000_000);
                if (i == 0) note("first_generate status=" + r.statusCode() + " zip_bytes=" + r.body().length);
            }
            note("generate_ms=" + times);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> post(wsdl).statusCode()));
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) codes.add(f.get());
            pool.shutdown();
            note("concurrent4_statuses=" + codes);
            note("cgroup_memory_after_load_mb=" + mb(run(20, "docker", "exec", NAME, "cat", "/sys/fs/cgroup/memory.current")));
            note("cgroup_memory_peak_mb=" + mb(run(20, "docker", "exec", NAME, "cat", "/sys/fs/cgroup/memory.peak")));
            note("oom_events=" + run(20, "docker", "exec", NAME, "cat", "/sys/fs/cgroup/memory.events").replace('\n', ' ').trim());
        } catch (Throwable e) {
            note("ERROR " + e);
        } finally {
            note("state=" + run(20, "docker", "inspect", NAME, "--format",
                    "{{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}").trim());
            run(30, "docker", "stop", "-t", "5", NAME);
            Files.writeString(Path.of("container-app-" + label + ".log"), run(30, "docker", "logs", NAME));
            run(30, "docker", "rm", NAME);
            note("DONE");
        }
    }

    static boolean running() throws IOException, InterruptedException {
        return run(20, "docker", "inspect", NAME, "--format", "{{.State.Running}}").trim().equals("true");
    }

    static void note(String s) throws IOException {
        Files.write(log, List.of(s), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    static String startedLine(String label) throws IOException, InterruptedException {
        for (String l : run(30, "docker", "logs", NAME).split("\n")) {
            if (l.contains("Started NecromancerApplication")) return l.substring(l.indexOf("Started"));
        }
        return "(not in log yet)";
    }

    static String mb(String bytes) {
        try {
            return String.valueOf(Long.parseLong(bytes.trim()) / (1024 * 1024));
        } catch (NumberFormatException e) {
            return "unknown(" + bytes.trim() + ")";
        }
    }

    /** Runs a command with a hard timeout, so a hung Docker daemon cannot block the harness. */
    static String run(int timeoutSeconds, String... cmd) throws IOException, InterruptedException {
        Path out = Files.createTempFile("cm", ".out");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "TIMEOUT(" + String.join(" ", cmd) + ")";
        }
        String s = Files.readString(out, StandardCharsets.UTF_8);
        Files.deleteIfExists(out);
        return s;
    }

    static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(120)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<byte[]> post(String wsdl) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/api/generate")).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString(wsdl)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
