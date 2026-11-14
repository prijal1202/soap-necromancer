package dev.soapnecromancer.generators;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The generator's output: relative path -> bytes, plus a deterministic zip
 * (generators Requirement 5.3).
 */
public final class GeneratedFiles {
    static final LocalDateTime ZIP_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Set<String> lowerCasePaths = new HashSet<>();
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

    /**
     * The zip boundary is the last line of defence, whatever the callers sanitized:
     * plain relative
     * paths of portable segments only, never a Windows device name, never two paths
     * that differ
     * only in case (they would overwrite each other when extracted on Windows or
     * macOS).
     */
    public void add(String path, byte[] content) {
        if (!isPortablePath(path)) {
            throw new IllegalArgumentException("unsafe generated path: " + path);
        }
        if (totalBytes + content.length > maxTotalBytes) {
            throw new GenerationLimitException("LIMIT_OUTPUT", "The generated project would exceed "
                    + maxTotalBytes + " bytes (at " + path + ")");
        }
        if (!lowerCasePaths.add(path.toLowerCase(Locale.ROOT)) || files.putIfAbsent(path, content) != null) {
            throw new IllegalStateException("generated path written twice (ignoring case): " + path);
        }
        totalBytes += content.length;
    }

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    static boolean isPortablePath(String path) {
        if (path.isEmpty() || path.length() > 240)
            return false;
        for (String segment : path.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.endsWith(".") || segment.contains("..")
                    || WINDOWS_DEVICE.matcher(segment).matches())
                return false;
        }
        return true;
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
