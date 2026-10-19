package io.agenticshortener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Repository scanning, snapshotting, policy checks, sandbox copying, diffing, and test execution. */
final class RepositorySupport {
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target", "build", ".gradle", "node_modules", ".agentic-runs");
    private static final int MAX_FILES = 5_000;
    private static final int MAX_CONTEXT_CHARS = 140_000;
    private static final int MAX_CHANGE_FILES = 100;
    private static final int MAX_CHANGE_BYTES = 2 * 1024 * 1024;

    private RepositorySupport() {}

    static Map<String, Object> inspect(Path root) throws Exception {
        List<Object> entries = new ArrayList<>();
        Map<String, Object> excerpts = new LinkedHashMap<>();
        int totalChars = 0;
        int count = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) continue;
                Path relative = root.relativize(path);
                if (hasSkippedSegment(relative)) continue;
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) continue;
                if (++count > MAX_FILES) break;
                long size = Files.size(path);
                String name = relative.toString().replace('\\', '/');
                entries.add(Map.of("path", name, "sizeBytes", size));
                if (size > 0 && size <= 20_000 && totalChars < MAX_CONTEXT_CHARS && isLikelyText(name)) {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    int remaining = MAX_CONTEXT_CHARS - totalChars;
                    if (content.length() > remaining) content = content.substring(0, remaining) + "\n[excerpt truncated]";
                    totalChars += content.length();
                    excerpts.put(name, content);
                }
            }
        }
        String gitStatus = gitStatus(root);
        return Map.of("root", root.toAbsolutePath().normalize().toString(),
                "fileCountScanned", Math.min(count, MAX_FILES),
                "files", entries,
                "textExcerpts", excerpts,
                "gitStatus", gitStatus);
    }

    static String fingerprint(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(path -> !path.equals(root))
                    .filter(path -> !hasSkippedSegment(root.relativize(path)))
                    .filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path))
                    .sorted().limit(MAX_FILES + 1L).toList();
        }
        if (files.size() > MAX_FILES) throw new IOException("Repository exceeds the 5,000-file safety limit.");
        for (Path path : files) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            digest.update((byte) 0xff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void copyTree(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(source)) continue;
                Path relative = source.relativize(path);
                if (hasSkippedSegment(relative) || Files.isSymbolicLink(path)) continue;
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else if (Files.isRegularFile(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static Map<String, String> validatedChanges(Object rawChanges) {
        if (!(rawChanges instanceof Map<?, ?> map)) throw new IllegalArgumentException("Agent proposal must contain a changes object.");
        if (map.size() > MAX_CHANGE_FILES) throw new IllegalArgumentException("Proposal exceeds the 100-file change limit.");
        Map<String, String> changes = new LinkedHashMap<>();
        int bytes = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String path = safeRelativePath(String.valueOf(entry.getKey()));
            if (!(entry.getValue() instanceof String content)) throw new IllegalArgumentException("Change content must be a UTF-8 string: " + path);
            rejectProtectedPath(path);
            int length = content.getBytes(StandardCharsets.UTF_8).length;
            bytes += length;
            if (length > 256 * 1024 || bytes > MAX_CHANGE_BYTES) throw new IllegalArgumentException("Proposal exceeds the source size safety limit.");
            changes.put(path, content);
        }
        return changes;
    }

    static List<String> validatedDeletions(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("deletions must be an array of relative paths.");
        if (list.size() > MAX_CHANGE_FILES) throw new IllegalArgumentException("Proposal exceeds the 100-file change limit.");
        List<String> result = new ArrayList<>();
        for (Object value : list) {
            String path = safeRelativePath(String.valueOf(value));
            rejectProtectedPath(path);
            result.add(path);
        }
        return result;
    }

    static void apply(Path root, Map<String, String> changes, List<String> deletions) throws IOException {
        for (String item : deletions) Files.deleteIfExists(root.resolve(safeRelativePath(item)));
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Path target = root.resolve(safeRelativePath(change.getKey())).normalize();
            if (!target.startsWith(root.normalize())) throw new IOException("Change escaped the sandbox.");
            if (Files.exists(target) && Files.isSymbolicLink(target)) throw new IOException("Refusing to overwrite a symbolic link: " + change.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, change.getValue(), StandardCharsets.UTF_8);
        }
    }

    static String makeDiff(Path before, Path after, List<String> paths) {
        StringBuilder out = new StringBuilder();
        for (String item : paths) {
            Path oldFile = before.resolve(item);
            Path newFile = after.resolve(item);
            try {
                if (Files.exists(oldFile) && Files.exists(newFile)) {
                    appendDiff(out, oldFile, newFile);
                } else if (Files.exists(newFile)) {
                    out.append("--- /dev/null\n+++ b/").append(item).append('\n');
                    for (String line : Files.readString(newFile).split("\\R", -1)) out.append('+').append(line).append('\n');
                } else if (Files.exists(oldFile)) {
                    out.append("--- a/").append(item).append("\n+++ /dev/null\n");
                    for (String line : Files.readString(oldFile).split("\\R", -1)) out.append('-').append(line).append('\n');
                }
            } catch (Exception ex) {
                out.append("[diff unavailable for ").append(item).append(": ").append(ex.getMessage()).append("]\n");
            }
        }
        return out.toString();
    }

    static List<String> buildCommand(Path root) {
        if (Files.isRegularFile(root.resolve("scripts/test.sh"))) return List.of("bash", "scripts/test.sh");
        if (Files.isRegularFile(root.resolve("mvnw"))) return List.of("sh", "./mvnw", "-q", "test");
        if (Files.isRegularFile(root.resolve("pom.xml")) && commandExists("mvn")) return List.of("mvn", "-q", "test");
        if (Files.isRegularFile(root.resolve("gradlew"))) return List.of("sh", "./gradlew", "test");
        if ((Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) && commandExists("gradle")) {
            return List.of("gradle", "test");
        }
        return List.of();
    }

    static ProcessResult execute(List<String> command, Path root, Path logFile, Path isolatedHome) throws Exception {
        Files.createDirectories(isolatedHome);
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(logFile.toFile());
        Map<String, String> env = builder.environment();
        String path = env.get("PATH");
        env.clear();
        if (path != null) env.put("PATH", path);
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) env.put("JAVA_HOME", javaHome);
        env.put("HOME", isolatedHome.toString());
        env.put("CI", "true");
        env.put("LANG", "C.UTF-8");
        Process process = builder.start();
        long start = System.nanoTime();
        boolean exited = process.waitFor(180, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        if (output.length() > 30_000) output = output.substring(output.length() - 30_000);
        return new ProcessResult(exited ? process.exitValue() : 124, !exited, elapsed, output);
    }

    private static void appendDiff(StringBuilder out, Path oldFile, Path newFile) throws Exception {
        Process process = new ProcessBuilder("git", "diff", "--no-index", "--", oldFile.toString(), newFile.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(5, TimeUnit.SECONDS);
        out.append(output);
    }

    private static String gitStatus(Path root) {
        try {
            Process process = new ProcessBuilder("git", "status", "--short", "--branch").directory(root.toFile())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : "not a Git worktree";
        } catch (Exception ex) { return "not available: " + ex.getClass().getSimpleName(); }
    }

    private static String safeRelativePath(String raw) {
        if (raw.isBlank() || raw.startsWith("/") || raw.startsWith("\\") || raw.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Changes must use safe repository-relative paths.");
        }
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.toString().isBlank() || path.startsWith("..")) {
            throw new IllegalArgumentException("Changes cannot escape the repository.");
        }
        String normalized = path.toString().replace('\\', '/');
        if (normalized.contains("../") || normalized.equals("..")) throw new IllegalArgumentException("Parent path segments are forbidden.");
        return normalized;
    }

    private static void rejectProtectedPath(String path) {
        String lower = path.toLowerCase();
        for (String segment : path.split("/")) {
            if (segment.equalsIgnoreCase(".git") || segment.equalsIgnoreCase(".ssh") || segment.equalsIgnoreCase(".aws") || segment.equalsIgnoreCase(".kube")) {
                throw new IllegalArgumentException("Proposal targets a protected path: " + path);
            }
        }
        if (lower.equals(".env") || lower.startsWith(".env.") || lower.endsWith(".pem") || lower.endsWith(".key") ||
                lower.contains("secret") || lower.contains("credential") || lower.startsWith(".github/workflows/")) {
            throw new IllegalArgumentException("Proposal targets a protected file: " + path);
        }
    }

    private static boolean hasSkippedSegment(Path relative) {
        for (Path part : relative) if (SKIP_DIRS.contains(part.toString())) return true;
        return false;
    }

    private static boolean isLikelyText(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".scala") || lower.endsWith(".xml") ||
                lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties") || lower.endsWith(".md") ||
                lower.endsWith(".json") || lower.endsWith(".sql") || lower.endsWith(".gradle") || lower.endsWith(".kts") ||
                lower.endsWith(".sh") || lower.endsWith(".txt") || lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".js");
    }

    private static boolean commandExists(String name) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String dir : path.split(java.io.File.pathSeparator)) if (Files.isExecutable(Path.of(dir, name))) return true;
        return false;
    }

    record ProcessResult(int exitCode, boolean timedOut, long elapsedMs, String output) {}
}
