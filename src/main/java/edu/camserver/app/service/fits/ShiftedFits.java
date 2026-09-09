package edu.camserver.app.service.fits;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Lossless bit-shift for 16-bit FITS images whose pixel values share trailing zero bits.
 *
 * <p>The all-sky cameras deliver 14-bit samples in 16-bit words, so every pixel is a multiple
 * of 4. Rice compression codes the differences between neighbouring pixels and cannot exploit
 * constant low bits, which costs two bits per pixel. Storing {@code value >> 2} with
 * {@code BSCALE = 4} removes them: any FITS reader that honours BSCALE reconstructs the original
 * values exactly, and {@link #restoring} turns such a stream back into the original 16-bit form.
 *
 * <p>The shift is recorded in the {@value #KEYWORD} keyword. A file without it passes through
 * {@link #restoring} untouched.
 */
public final class ShiftedFits {
    /** Header keyword holding the number of bits the stored values were shifted right. */
    public static final String KEYWORD = "CSBITSHF";
    public static final int MAX_SHIFT = 8;
    private static final int BUFFER = 256 * 1024;

    private ShiftedFits() {
    }

    /**
     * What a plain image FITS contains, as far as the archive cares: the header facts that must
     * survive a round trip, the data length and CRC32 of the raw data bytes, and the largest
     * shift (up to the requested one) that loses nothing. 0 means the data cannot be shifted.
     */
    public record Scan(String essentials, long dataBytes, long dataCrc, int losslessShift) {
        public boolean matches(Scan other) {
            return essentials.equals(other.essentials) && dataBytes == other.dataBytes && dataCrc == other.dataCrc;
        }
    }

    /** Scans the primary HDU of a plain FITS file. */
    public static Scan scan(Path plain, int wantedShift) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(plain), BUFFER)) {
            return scan(in, wantedShift, false);
        }
    }

    /**
     * Scans the primary HDU of a stream (as produced by {@link #restoring}) and drains the rest,
     * so a producing process can finish. Only the essentials, length and CRC are meaningful.
     */
    public static Scan scanStream(InputStream in) throws IOException {
        return scan(in, 0, true);
    }

    private static Scan scan(InputStream in, int wantedShift, boolean drain) throws IOException {
        FitsHeader header = FitsHeader.read(in);
        long dataBytes = header.dataBytes();
        boolean shiftable = wantedShift > 0
                && header.bitpix() == 16
                && header.longValue("BSCALE").orElse(1) == 1
                && !header.has("BLANK")
                && dataBytes > 0
                && dataBytes % 2 == 0;
        int shift = Math.min(Math.max(wantedShift, 0), MAX_SHIFT);

        CRC32 crc = new CRC32();
        byte[] buffer = new byte[BUFFER];
        long remaining = dataBytes;
        int orBits = 0;
        boolean odd = false;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new EOFException("FITS data unit is shorter than its header declares ("
                        + (dataBytes - remaining) + " of " + dataBytes + " bytes)");
            }
            crc.update(buffer, 0, n);
            if (shiftable) {
                // The mask test only needs the low byte of each 16-bit big-endian word, i.e. every odd byte.
                int start = odd ? 0 : 1;
                for (int i = start; i < n; i += 2) {
                    orBits |= buffer[i] & 0xff;
                }
                if ((n & 1) == 1) {
                    odd = !odd; // an odd-length read leaves the next chunk starting mid-word
                }
            }
            remaining -= n;
        }
        if (drain) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        int lossless = 0;
        if (shiftable) {
            lossless = orBits == 0 ? shift : Math.min(shift, Integer.numberOfTrailingZeros(orBits));
        }
        return new Scan(essentials(header), dataBytes, crc.getValue(), lossless);
    }

    /** The header facts that must be identical before and after an archive round trip. */
    public static String essentials(FitsHeader header) {
        StringBuilder sb = new StringBuilder();
        sb.append("BITPIX=").append(header.bitpix());
        sb.append(";NAXIS=").append(header.naxis());
        long[] axes = header.axes();
        for (int i = 0; i < axes.length; i++) {
            sb.append(";NAXIS").append(i + 1).append('=').append(axes[i]);
        }
        sb.append(";BSCALE=").append(formatNumber(header.doubleValue("BSCALE").orElse(1.0)));
        sb.append(";BZERO=").append(formatNumber(header.doubleValue("BZERO").orElse(0.0)));
        return sb.toString();
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.10g", value);
    }

    /**
     * Writes a copy of {@code plain} whose primary data unit is shifted right by {@code shift}
     * bits, with BSCALE and {@value #KEYWORD} set so readers reconstruct the original values.
     * Anything after the primary HDU is copied unchanged.
     */
    public static void writeShifted(Path plain, Path out, int shift) throws IOException {
        if (shift <= 0 || shift > MAX_SHIFT) {
            throw new IllegalArgumentException("shift must be 1.." + MAX_SHIFT + ": " + shift);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(plain), BUFFER);
             OutputStream os = new BufferedOutputStream(
                     Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER)) {
            FitsHeader header = FitsHeader.read(in);
            if (header.bitpix() != 16) {
                throw new IOException("Bit shift needs BITPIX = 16, found " + header.bitpix());
            }
            if (header.longValue("BSCALE").orElse(1) != 1) {
                throw new IOException("Bit shift needs BSCALE = 1");
            }
            long dataBytes = header.dataBytes();
            header.setLong("BSCALE", 1L << shift, "pixel values stored >> " + KEYWORD + "; readers apply BSCALE");
            header.setLong(KEYWORD, shift, "bits trimmed by the CamServer archive, lossless");
            os.write(header.toBytes());

            byte[] buffer = new byte[BUFFER];
            long remaining = dataBytes;
            int carry = -1;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new EOFException("FITS data unit is shorter than its header declares");
                }
                carry = shiftInPlace(buffer, n, carry, -shift, os);
                remaining -= n;
            }
            in.transferTo(os);
        }
    }

    /**
     * Wraps a plain FITS stream so that, if its primary HDU carries {@value #KEYWORD}, the
     * shift is undone: BSCALE goes back to 1, the marker and any now-stale checksum cards are
     * dropped, and every data value is shifted left again. Streams without the marker are
     * passed through byte for byte.
     */
    public static InputStream restoring(InputStream in) {
        return new RestoringInputStream(in);
    }

    /**
     * Shifts the 16-bit big-endian words in {@code buffer[0..n)} and writes them to {@code out}.
     * A negative shift shifts right (arithmetic), positive shifts left. {@code carry} holds the
     * first byte of a word split across calls, or -1. Returns the new carry.
     */
    static int shiftInPlace(byte[] buffer, int n, int carry, int shift, OutputStream out) throws IOException {
        int i = 0;
        if (carry >= 0 && n > 0) {
            short v = (short) ((carry << 8) | (buffer[0] & 0xff));
            short s = shift < 0 ? (short) (v >> -shift) : (short) (v << shift);
            out.write(s >> 8);
            out.write(s);
            i = 1;
            carry = -1;
        }
        int end = i + ((n - i) & ~1);
        for (int j = i; j < end; j += 2) {
            short v = (short) ((buffer[j] << 8) | (buffer[j + 1] & 0xff));
            short s = shift < 0 ? (short) (v >> -shift) : (short) (v << shift);
            buffer[j] = (byte) (s >> 8);
            buffer[j + 1] = (byte) s;
        }
        out.write(buffer, i, end - i);
        if (end < n) {
            carry = buffer[end] & 0xff;
        }
        return carry;
    }

    /** Streaming inverse of {@link #writeShifted}. */
    private static final class RestoringInputStream extends InputStream {
        private final InputStream in;
        private byte[] pending;          // transformed header, then transformed data chunks
        private int pendingPos;
        private boolean headerDone;
        private int shift;
        private long dataRemaining;
        private int carry = -1;
        private final byte[] chunk = new byte[BUFFER];
        private boolean eof;

        RestoringInputStream(InputStream in) {
            this.in = in;
        }

        private void readHeader() throws IOException {
            FitsHeader header = FitsHeader.read(in);
            long declared = header.longValue(KEYWORD).orElse(0);
            if (declared > 0) {
                if (declared > MAX_SHIFT || header.bitpix() != 16
                        || header.longValue("BSCALE").orElse(1) != (1L << declared)) {
                    throw new IOException("Inconsistent " + KEYWORD + "/BSCALE/BITPIX in shifted FITS header");
                }
                shift = (int) declared;
                dataRemaining = header.dataBytes();
                header.setLong("BSCALE", 1, null);
                header.remove(KEYWORD);
                header.remove("CHECKSUM");
                header.remove("DATASUM");
            } else {
                shift = 0;
                dataRemaining = 0;
            }
            pending = header.toBytes();
            pendingPos = 0;
            headerDone = true;
        }

        private boolean fill() throws IOException {
            if (!headerDone) {
                readHeader();
                return true;
            }
            if (eof) {
                return false;
            }
            int want = shift > 0 && dataRemaining > 0 ? (int) Math.min(chunk.length, dataRemaining) : chunk.length;
            int n = in.read(chunk, 0, want);
            if (n < 0) {
                eof = true;
                if (dataRemaining > 0) {
                    throw new EOFException("Shifted FITS data unit ended early (" + dataRemaining + " bytes missing)");
                }
                return false;
            }
            if (shift > 0 && dataRemaining > 0) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(n + 2);
                carry = shiftInPlace(chunk, n, carry, shift, bos);
                pending = bos.toByteArray();
                dataRemaining -= n;
            } else {
                pending = java.util.Arrays.copyOf(chunk, n);
            }
            pendingPos = 0;
            return true;
        }

        @Override
        public int read() throws IOException {
            while (pending == null || pendingPos >= pending.length) {
                if (!fill()) {
                    return -1;
                }
            }
            return pending[pendingPos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (pending == null || pendingPos >= pending.length) {
                if (!fill()) {
                    return -1;
                }
            }
            int n = Math.min(len, pending.length - pendingPos);
            System.arraycopy(pending, pendingPos, b, off, n);
            pendingPos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
