package dev.soapnecromancer.generators;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The generator's output: relative path -> bytes, plus a deterministic zip
 * (generators Requirement 5.3).
 */
public final class GeneratedFiles {
    static final LocalDateTime ZIP_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final long maxTotalBytes;
    private long totalBytes;

    public GeneratedFiles() {
        this(Long.MAX_VALUE);
    }

    /**
     * @param maxTotalBytes cap on the sum of all file sizes; exceeding it throws
     *                      GenerationLimitException
     */
    public GeneratedFiles(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    public long totalBytes() {
        return totalBytes;
    }

    /** Text files are stored UTF-8 with LF line endings. */
    public void add(String path, String text) {
        add(path, text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
    }

    public void add(String path, byte[] content) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("//")
                || List.of(path.split("/")).stream().anyMatch(p -> p.equals("..") || p.equals("."))) {
            throw new IllegalArgumentException("unsafe generated path: " + path);
        }
        if (totalBytes + content.length > maxTotalBytes) {
            throw new GenerationLimitException("LIMIT_OUTPUT", "The generated project would exceed "
                    + maxTotalBytes + " bytes (at " + path + ")");
        }
        if (files.putIfAbsent(path, content.clone()) != null) {
            throw new IllegalStateException("generated path written twice: " + path);
        }
        totalBytes += content.length;
    }

    public Map<String, byte[]> files() {
        return Collections.unmodifiableMap(files);
    }

    public String text(String path) {
        byte[] b = files.get(path);
        if (b == null)
            throw new IllegalArgumentException("not generated: " + path);
        return new String(b, StandardCharsets.UTF_8);
    }

    /**
     * Entries sorted by path, fixed timestamps: the same input always yields
     * identical bytes.
     */
    public byte[] zip() {
        List<String> names = new ArrayList<>(files.keySet());
        Collections.sort(names);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String name : names) {
                ZipEntry e = new ZipEntry(name);
                e.setTimeLocal(ZIP_TIME);
                zip.putNextEntry(e);
                zip.write(files.get(name));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
