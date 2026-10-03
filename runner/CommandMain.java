import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Fixed trusted entrypoint; candidate data is never interpreted as build configuration. */
public final class CommandMain {
    static final int LOG_LIMIT = 256 * 1024, JAR_LIMIT = 16 * 1024 * 1024;
    static final class Capture {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean truncated;
        synchronized void append(byte[] b, int n) {
            int keep = Math.min(n, LOG_LIMIT - bytes.size());
            bytes.write(b, 0, keep);
            truncated |= keep < n;
        }
        synchronized String encoded() { return Base64.getEncoder().encodeToString(bytes.toByteArray()); }
    }
    static long deadline;
    static final java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean();
    static long remaining() throws TimeoutException {
        long n = deadline - System.currentTimeMillis();
        if (n <= 0) throw new TimeoutException();
        return n;
    }
    static void copyCache() throws Exception {
        Path source = Path.of("/opt/gradle-home"), dest = Path.of("/work/gradle");
        try (var paths = Files.walk(source)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                remaining();
                Path out = dest.resolve(source.relativize(p));
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(out);
                else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                else throw new IOException("invalid_cache_entry");
            }
        }
    }
    static Thread drain(InputStream in, Capture logs) {
        Thread t = new Thread(() -> {
            try (in) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) >= 0) logs.append(b, n); }
            catch (IOException ignored) { }
        });
        t.setDaemon(true); t.start(); return t;
    }
    static byte[] artifact() throws IOException {
        Path jar = Path.of("/work/build/libs/task-tracker.jar");
        for (Path p = jar.getParent(); p != null; p = p.getParent())
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("nonregular");
        if (!Files.exists(jar, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing");
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) throw new IOException("nonregular");
        try (var channel = Files.newByteChannel(jar, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            if (channel.size() > JAR_LIMIT) throw new IOException("oversized");
            byte[] data = input.readNBytes(JAR_LIMIT + 1);
            if (data.length > JAR_LIMIT) throw new IOException("oversized");
            return data;
        }
    }
    static String reports() throws IOException {
        Path root = Path.of("/work/build/test-results/test");
        for (Path p = root; p != null; p = p.getParent())
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int total = 0, count = 0;
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var files = Files.newDirectoryStream(root, "*.xml")) {
            for (Path file : files) {
                if (++count > 256 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "";
                byte[] data;
                try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                     var input = Channels.newInputStream(channel)) {
                    data = input.readNBytes(4 * 1024 * 1024 - total + 1);
                }
                total += data.length;
                if (total > 4 * 1024 * 1024) return "";
                zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                zip.write(data); zip.closeEntry();
            }
        }
        return bytes.size() <= 4 * 1024 * 1024 ? Base64.getEncoder().encodeToString(bytes.toByteArray()) : "";
    }
    public static void main(String[] args) {
        Capture logs = new Capture(); Process child = null; int code = 70;
        String jar = "", artifactError = "", reports = "";
        try {
            deadline = Long.parseLong(System.getenv("HARNESS_DEADLINE_EPOCH_MILLIS"));
            if (deadline - System.currentTimeMillis() > 120_000) throw new IllegalArgumentException();
            remaining();
            Thread watchdog = new Thread(() -> {
                try { Thread.sleep(Math.max(1, deadline - System.currentTimeMillis())); }
                catch (InterruptedException ignored) { return; }
                ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
                if (emitted.compareAndSet(false, true)) {
                    System.out.println("HARNESS_RESULT_V1\t" + logs.encoded() + "\t" + logs.truncated + "\t\t\t");
                    System.out.flush();
                }
                Runtime.getRuntime().halt(124);
            });
            watchdog.setDaemon(true); watchdog.start();
            String operation = System.getenv("HARNESS_OPERATION");
            if (!Set.of("BUILD", "TEST").contains(operation)) throw new IllegalArgumentException();
            while (!Files.isRegularFile(Path.of("/control/ready"), LinkOption.NOFOLLOW_LINKS))
                Thread.sleep(Math.min(25, remaining()));
            copyCache(); remaining();
            ProcessBuilder builder = new ProcessBuilder("/opt/gradle/bin/gradle", "--offline", "--no-daemon", "--console=plain", "--max-workers=1", operation.equals("BUILD") ? "jar" : "test", "--project-cache-dir", "/work/cache", "-p", "/work/project");
            builder.environment().clear();
            builder.environment().putAll(Map.of("JAVA_HOME", "/opt/java/openjdk", "PATH", "/opt/java/openjdk/bin:/usr/bin:/bin", "GRADLE_USER_HOME", "/work/gradle", "HOME", "/work", "LANG", "C.UTF-8"));
            child = builder.start();
            Thread stdout = drain(child.getInputStream(), logs), stderr = drain(child.getErrorStream(), logs);
            if (!child.waitFor(remaining(), TimeUnit.MILLISECONDS)) throw new TimeoutException();
            code = child.exitValue();
            stdout.join(Math.min(1000, remaining())); stderr.join(Math.min(1000, remaining()));
            if (operation.equals("BUILD")) {
                try { jar = Base64.getEncoder().encodeToString(artifact()); }
                catch (IOException e) { artifactError = Set.of("missing", "oversized", "nonregular").contains(e.getMessage()) ? e.getMessage() : "unreadable"; }
            }
            if (operation.equals("TEST")) { try { reports = reports(); } catch (IOException ignored) { } }
        } catch (TimeoutException e) { code = 124; }
          catch (Exception e) { code = 70; }
        finally {
            if (child != null && child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
        }
        if (emitted.compareAndSet(false, true)) System.out.println("HARNESS_RESULT_V1\t" + logs.encoded() + "\t" + logs.truncated + "\t" + jar + "\t" + artifactError + "\t" + reports);
        System.exit(code);
    }
}
