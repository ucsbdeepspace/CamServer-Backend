package edu.camserver.app.service.fits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * FITS tile compression (Rice) for the image archive, built on the CFITSIO command-line tools.
 *
 * <p>{@code fpack} writes the compressed file and {@code imcopy} reads any tile-compressed image
 * back, including frames the cameras uploaded already Rice-compressed by astropy, which
 * {@code funpack} refuses. Before packing, 16-bit frames whose pixels share trailing zero bits
 * are shifted with {@link ShiftedFits}; the shift is undone transparently on every read.
 *
 * <p>Every compression is verified before it is trusted: the new file is decoded again and its
 * image dimensions, scaling keywords, data length and data CRC32 must equal the source's.
 */
public final class RiceArchiver {
    private static final Logger log = LoggerFactory.getLogger(RiceArchiver.class);
    private static final int BUFFER = 256 * 1024;
    private static final int MAX_TOOL_OUTPUT = 4000;

    private final String fpackCommand;
    private final String imcopyCommand;
    private final int shiftBits;
    private final Duration toolTimeout;
    private final Path tempDir;

    public RiceArchiver(String fpackCommand, String imcopyCommand, int shiftBits, Duration toolTimeout, Path tempDir) {
        this.fpackCommand = fpackCommand == null || fpackCommand.isBlank() ? "fpack" : fpackCommand.trim();
        this.imcopyCommand = imcopyCommand == null || imcopyCommand.isBlank() ? "imcopy" : imcopyCommand.trim();
        this.shiftBits = Math.max(0, Math.min(ShiftedFits.MAX_SHIFT, shiftBits));
        this.toolTimeout = toolTimeout == null || toolTimeout.isZero() || toolTimeout.isNegative()
                ? Duration.ofMinutes(5) : toolTimeout;
        this.tempDir = tempDir;
    }

    public String fpackCommand() {
        return fpackCommand;
    }

    public String imcopyCommand() {
        return imcopyCommand;
    }

    public int shiftBits() {
        return shiftBits;
    }

    /** Whether both tools can be executed, with the fpack version when they can. */
    public record Availability(boolean available, String fpackVersion, String detail) {
    }

    public Availability probe() {
        String version;
        try {
            ToolOutput out = run(List.of(fpackCommand, "-V"), "fpack -V");
            version = out.text().lines().findFirst().orElse("").trim();
        } catch (IOException e) {
            return new Availability(false, null, fpackCommand + ": " + e.getMessage());
        }
        try {
            // imcopy prints its usage and exits non-zero without arguments; only failing to start matters.
            Process p = new ProcessBuilder(imcopyCommand).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor(toolTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            return new Availability(false, version, imcopyCommand + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Availability(false, version, "interrupted while probing " + imcopyCommand);
        }
        return new Availability(true, version, "fpack " + version + ", " + imcopyCommand + " found");
    }

    /** Outcome of one compression: size of the new file and the bit shift that was applied. */
    public record Result(long bytes, int shift) {
    }

    /**
     * Compresses {@code source} (a plain FITS, or one that already holds a tile-compressed
     * image) into {@code target}. The source is left in place; the caller deletes it once the
     * result is in use. Fails, leaving nothing behind, when the round trip does not verify.
     */
    public Result compress(Path source, Path target) throws IOException {
        Path fzTmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        List<Path> temps = new ArrayList<>(2);
        try {
            FitsLayout layout = FitsLayout.inspect(source);
            Path plain;
            if (layout.plainImageHdu() == 0) {
                plain = source;
            } else if (layout.compressedImageHdu() >= 0) {
                plain = tempFile(source, ".plain");
                temps.add(plain);
                imcopyToFile(source, layout.compressedImageHdu(), plain);
            } else if (layout.plainImageHdu() > 0) {
                plain = tempFile(source, ".plain");
                temps.add(plain);
                imcopyToFile(source, layout.plainImageHdu(), plain);
            } else {
                throw new IOException("No image HDU found in " + source.getFileName());
            }

            ShiftedFits.Scan scan = ShiftedFits.scan(plain, shiftBits);
            Path fpackInput = plain;
            if (scan.losslessShift() > 0) {
                Path shifted = tempFile(source, ".shift");
                temps.add(shifted);
                ShiftedFits.writeShifted(plain, shifted, scan.losslessShift());
                fpackInput = shifted;
            }

            runFpack(fpackInput, fzTmp);
            verify(fzTmp, scan);

            Files.setLastModifiedTime(fzTmp, Files.getLastModifiedTime(source));
            moveReplacing(fzTmp, target);
            return new Result(Files.size(target), scan.losslessShift());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(fzTmp);
            throw e;
        } finally {
            for (Path temp : temps) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not delete temp file {}: {}", temp, e.getMessage());
                }
            }
        }
    }

    private void verify(Path fz, ShiftedFits.Scan expected) throws IOException {
        FitsLayout layout = FitsLayout.inspect(fz);
        if (layout.compressedImageHdu() < 0) {
            throw new IOException("fpack output holds no compressed image");
        }
        ShiftedFits.Scan actual;
        try (InputStream in = openRestored(fz, layout.compressedImageHdu())) {
            actual = ShiftedFits.scanStream(in);
        }
        if (!expected.matches(actual)) {
            throw new IOException("Rice verification failed: expected [" + expected.essentials() + ", "
                    + expected.dataBytes() + " bytes, crc " + Long.toHexString(expected.dataCrc()) + "] but decoded ["
                    + actual.essentials() + ", " + actual.dataBytes() + " bytes, crc "
                    + Long.toHexString(actual.dataCrc()) + "]");
        }
    }

    /** Expands {@code fz} into a plain FITS at {@code target}; the source is left in place. */
    public long decompress(Path fz, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        try {
            try (InputStream in = openRestored(fz);
                 OutputStream out = new BufferedOutputStream(
                         Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER)) {
                in.transferTo(out);
            }
            FitsLayout layout = FitsLayout.inspect(tmp);
            if (layout.plainImageHdu() != 0) {
                throw new IOException("Decoded file does not start with a plain image HDU");
            }
            long needed = layout.primary().byteLength() + layout.primary().paddedDataBytes();
            if (Files.size(tmp) < needed) {
                throw new IOException("Decoded file is truncated: " + Files.size(tmp) + " < " + needed + " bytes");
            }
            Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(fz));
            moveReplacing(tmp, target);
            return Files.size(target);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Opens a tile-compressed file as a plain FITS stream, with any bit shift undone. */
    public InputStream openRestored(Path fz) throws IOException {
        FitsLayout layout = FitsLayout.inspect(fz);
        if (layout.compressedImageHdu() < 0) {
            throw new IOException("No compressed image HDU in " + fz.getFileName());
        }
        return openRestored(fz, layout.compressedImageHdu());
    }

    /**
     * Opens any FITS file holding an image as a plain FITS stream with any bit shift undone:
     * a plain frame is read directly, a tile-compressed one (whatever its name) through imcopy.
     */
    public InputStream openImage(Path file) throws IOException {
        FitsLayout layout = FitsLayout.inspect(file);
        if (layout.plainImageHdu() == 0) {
            return ShiftedFits.restoring(new BufferedInputStream(Files.newInputStream(file), BUFFER));
        }
        int hdu = layout.compressedImageHdu() >= 0 ? layout.compressedImageHdu() : layout.plainImageHdu();
        if (hdu < 0) {
            throw new IOException("No image HDU found in " + file.getFileName());
        }
        return openRestored(file, hdu);
    }

    private InputStream openRestored(Path file, int hdu) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(imcopyCommand, file + "[" + hdu + "]", "-");
        Process process = builder.start();
        Drain stderr = new Drain(process.getErrorStream());
        return ShiftedFits.restoring(new ProcessInputStream(process, stderr, imcopyCommand, toolTimeout));
    }

    /**
     * Rice-compresses {@code input} into {@code output}. fpack writes to stdout with {@code -S};
     * older CFITSIO builds (e.g. Ubuntu 18.04's) have no {@code -O} flag to name the output.
     */
    private void runFpack(Path input, Path output) throws IOException {
        List<String> command = List.of(fpackCommand, "-r", "-Y", "-S", input.toString());
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile()).start();
        Drain stderr = new Drain(process.getErrorStream());
        waitFor(process, "fpack", command);
        String errors = stderr.text();
        if (process.exitValue() != 0) {
            throw new IOException("fpack failed (exit " + process.exitValue() + "): " + errors.strip());
        }
        if (!Files.isRegularFile(output) || Files.size(output) < FitsHeader.BLOCK) {
            throw new IOException("fpack produced no output for " + input.getFileName()
                    + (errors.isBlank() ? "" : ": " + errors.strip()));
        }
    }

    private void imcopyToFile(Path file, int hdu, Path out) throws IOException {
        run(List.of(imcopyCommand, file + "[" + hdu + "]", out.toString()), "imcopy");
        if (!Files.isRegularFile(out)) {
            throw new IOException("imcopy produced no output for " + file.getFileName());
        }
    }

    private Path tempFile(Path source, String kind) throws IOException {
        Files.createDirectories(tempDir);
        return tempDir.resolve(source.getFileName() + kind + ".tmp-" + System.nanoTime());
    }

    // ------------------------------------------------------------- processes

    private record ToolOutput(int exitCode, String text) {
    }

    private ToolOutput run(List<String> command, String what) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Drain output = new Drain(process.getInputStream());
        waitFor(process, what, command);
        String text = output.text();
        if (process.exitValue() != 0) {
            throw new IOException(what + " failed (exit " + process.exitValue() + "): " + text.strip());
        }
        return new ToolOutput(0, text);
    }

    private void waitFor(Process process, String what, List<String> command) throws IOException {
        boolean finished;
        try {
            finished = process.waitFor(toolTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(what + " interrupted");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException(what + " timed out after " + toolTimeout.toSeconds() + "s: " + String.join(" ", command));
        }
    }

    /** Reads a process stream on a daemon thread into a bounded buffer. */
    private static final class Drain {
        private final Thread thread;
        private final StringBuilder text = new StringBuilder();

        Drain(InputStream stream) {
            thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = reader.read(buf)) >= 0) {
                        synchronized (text) {
                            if (text.length() < MAX_TOOL_OUTPUT) {
                                text.append(buf, 0, Math.min(n, MAX_TOOL_OUTPUT - text.length()));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process gone
                }
            }, "archive-tool-output");
            thread.setDaemon(true);
            thread.start();
        }

        String text() {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (text) {
                return text.toString();
            }
        }
    }

    /** stdout of a running tool; a non-zero exit at end of stream becomes an IOException. */
    private static final class ProcessInputStream extends FilterInputStream {
        private final Process process;
        private final Drain stderr;
        private final String name;
        private final Duration timeout;
        private boolean checked;

        ProcessInputStream(Process process, Drain stderr, String name, Duration timeout) {
            super(process.getInputStream());
            this.process = process;
            this.stderr = stderr;
            this.name = name;
            this.timeout = timeout;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                checkExit();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) {
                checkExit();
            }
            return n;
        }

        private void checkExit() throws IOException {
            if (checked) {
                return;
            }
            checked = true;
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException(name + " interrupted");
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(name + " did not exit after closing its output");
            }
            if (process.exitValue() != 0) {
                throw new IOException(name + " failed (exit " + process.exitValue() + "): " + stderr.text().strip());
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Human-readable description for logs and the config endpoint. */
    public Optional<String> describe() {
        Availability availability = probe();
        return Optional.of(availability.available()
                ? "rice (" + availability.detail() + ", shift " + shiftBits + " bit(s))"
                : "rice tools missing: " + availability.detail());
    }
}
