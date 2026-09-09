package edu.camserver.app.service.fits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiftedFitsTest {

    @TempDir
    Path dir;

    @Test
    void detectsSharedTrailingZeroBits() throws IOException {
        Path multiplesOf4 = TestFits.write(dir, "m4.fits", TestFits.cameraLike(50, 20, 3, 1));
        assertEquals(2, ShiftedFits.scan(multiplesOf4, 2).losslessShift());
        assertEquals(1, ShiftedFits.scan(multiplesOf4, 1).losslessShift());
        assertEquals(2, ShiftedFits.scan(multiplesOf4, 8).losslessShift(), "capped by the data, not the request");

        Path odd = TestFits.write(dir, "odd.fits", TestFits.plain16(50, 20, 1, i -> i == 777 ? 1001 : i * 4));
        assertEquals(0, ShiftedFits.scan(odd, 2).losslessShift(), "one odd pixel forbids any shift");

        Path zeros = TestFits.write(dir, "zero.fits", TestFits.plain16(8, 8, 1, i -> 0));
        assertEquals(2, ShiftedFits.scan(zeros, 2).losslessShift());
        assertEquals(0, ShiftedFits.scan(zeros, 0).losslessShift(), "shift disabled");
    }

    @Test
    void shiftAndRestoreAreExactInverses() throws IOException {
        byte[] original = TestFits.cameraLike(37, 11, 3, 7);   // odd row length: data not block aligned
        Path plain = TestFits.write(dir, "frame.fits", original);
        Path shifted = dir.resolve("frame.shift.fits");
        ShiftedFits.writeShifted(plain, shifted, 2);

        FitsHeader shiftedHeader = FitsHeader.read(new ByteArrayInputStream(Files.readAllBytes(shifted)));
        assertEquals(4, shiftedHeader.longValue("BSCALE").orElseThrow());
        assertEquals(32768, shiftedHeader.longValue("BZERO").orElseThrow(), "BZERO is untouched");
        assertEquals(2, shiftedHeader.longValue(ShiftedFits.KEYWORD).orElseThrow());
        assertEquals(Files.size(plain), Files.size(shifted));

        // every stored value is the original >> 2 (arithmetic)
        byte[] s = Files.readAllBytes(shifted);
        int off = shiftedHeader.byteLength();
        for (int i = 0; i < 37 * 11 * 3 * 2; i += 2) {
            short o = (short) ((original[off + i] << 8) | (original[off + i + 1] & 0xff));
            short v = (short) ((s[off + i] << 8) | (s[off + i + 1] & 0xff));
            assertEquals((short) (o >> 2), v);
        }

        byte[] restored;
        try (InputStream in = ShiftedFits.restoring(new ByteArrayInputStream(s))) {
            restored = in.readAllBytes();
        }
        assertArrayEquals(original, restored, "restored file is byte-identical to the original");
    }

    @Test
    void restoringPassesUnshiftedStreamsThroughUnchanged() throws IOException {
        byte[] original = TestFits.plain16(9, 5, 1, i -> i * 3);
        try (InputStream in = ShiftedFits.restoring(new ByteArrayInputStream(original))) {
            assertArrayEquals(original, in.readAllBytes());
        }
    }

    @Test
    void restoringDropsStaleChecksumCards() throws IOException {
        byte[] original = TestFits.cameraLike(16, 4, 1, 3);
        Path plain = TestFits.write(dir, "c.fits", original);
        Path shifted = dir.resolve("c.shift.fits");
        ShiftedFits.writeShifted(plain, shifted, 2);
        byte[] s = Files.readAllBytes(shifted);
        FitsHeader header = FitsHeader.read(new ByteArrayInputStream(s));
        header.setLong("DATASUM", 12345, "stale");
        ByteArrayOutputStream withChecksum = new ByteArrayOutputStream();
        withChecksum.writeBytes(header.toBytes());
        withChecksum.write(s, header.byteLength(), s.length - header.byteLength());

        byte[] restored;
        try (InputStream in = ShiftedFits.restoring(new ByteArrayInputStream(withChecksum.toByteArray()))) {
            restored = in.readAllBytes();
        }
        FitsHeader restoredHeader = FitsHeader.read(new ByteArrayInputStream(restored));
        assertTrue(!restoredHeader.has("DATASUM"));
        assertTrue(!restoredHeader.has(ShiftedFits.KEYWORD));
        assertEquals(1, restoredHeader.longValue("BSCALE").orElseThrow());
        ShiftedFits.Scan a = ShiftedFits.scanStream(new ByteArrayInputStream(original));
        ShiftedFits.Scan b = ShiftedFits.scanStream(new ByteArrayInputStream(restored));
        assertTrue(a.matches(b));
    }

    @Test
    void scanStreamReportsTruncation() {
        byte[] original = TestFits.plain16(9, 5, 1, i -> i);
        byte[] truncated = java.util.Arrays.copyOf(original, FitsHeader.BLOCK + 10);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ShiftedFits.scanStream(new ByteArrayInputStream(truncated)));
    }

    @Test
    void scanAndRestoreWorkAcrossBufferBoundaries() throws IOException {
        // 701 x 301 x 3 px = 1.27 MB of data: several 256 KB buffers, not block aligned
        byte[] original = TestFits.cameraLike(701, 301, 3, 13);
        Path plain = TestFits.write(dir, "big.fits", original);
        assertEquals(2, ShiftedFits.scan(plain, 2).losslessShift());

        Path shifted = dir.resolve("big.shift.fits");
        ShiftedFits.writeShifted(plain, shifted, 2);
        try (InputStream in = ShiftedFits.restoring(Files.newInputStream(shifted))) {
            assertArrayEquals(original, in.readAllBytes());
        }
    }

    @Test
    void oddSizedReadsFromAPipeAreHandled() throws IOException {
        byte[] original = TestFits.cameraLike(333, 41, 3, 17);
        Path plain = TestFits.write(dir, "pipe.fits", original);
        Path shifted = dir.resolve("pipe.shift.fits");
        ShiftedFits.writeShifted(plain, shifted, 2);
        byte[] s = Files.readAllBytes(shifted);

        // process pipes hand back arbitrary chunk sizes; 3-byte reads split every other word
        try (InputStream in = ShiftedFits.restoring(new Dribble(new ByteArrayInputStream(s), 3))) {
            assertArrayEquals(original, in.readAllBytes());
        }
        try (InputStream in = ShiftedFits.restoring(new Dribble(new ByteArrayInputStream(s), 1))) {
            assertArrayEquals(original, in.readAllBytes());
        }
        ShiftedFits.Scan viaPipe = ShiftedFits.scanStream(new Dribble(new ByteArrayInputStream(original), 3));
        assertTrue(ShiftedFits.scan(plain, 0).matches(viaPipe));
        // the trailing-zero detection must not be confused by odd reads either
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertEquals(2, ShiftedFits.scan(plain, 2).losslessShift());
        Path odd = TestFits.write(dir, "pipeodd.fits", TestFits.plain16(333, 41, 1, i -> i == 4000 ? 2 : i * 4));
        assertEquals(1, ShiftedFits.scan(odd, 2).losslessShift());
    }

    /** Returns at most {@code max} bytes per read, like a slow pipe. */
    private static final class Dribble extends java.io.FilterInputStream {
        private final int max;

        Dribble(InputStream in, int max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(len, max));
        }
    }

    @Test
    void singleByteReadsWork() throws IOException {
        byte[] original = TestFits.cameraLike(5, 3, 1, 9);
        Path plain = TestFits.write(dir, "b.fits", original);
        Path shifted = dir.resolve("b.shift.fits");
        ShiftedFits.writeShifted(plain, shifted, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = ShiftedFits.restoring(Files.newInputStream(shifted))) {
            int b;
            while ((b = in.read()) >= 0) {
                out.write(b);
            }
        }
        assertArrayEquals(original, out.toByteArray());
    }
}
