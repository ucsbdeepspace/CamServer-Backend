package edu.camserver.app.service.fits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Builds small synthetic 16-bit FITS frames the way astropy writes uint16 data (BZERO = 32768). */
final class TestFits {
    private TestFits() {
    }

    static byte[] plain16(int width, int height, int planes, IntUnaryOperator valueAt) {
        List<String> cards = new ArrayList<>();
        cards.add(FitsHeader.formatCard("SIMPLE", "T", "conforms to FITS standard"));
        cards.add(FitsHeader.formatCard("BITPIX", "16", "array data type"));
        cards.add(FitsHeader.formatCard("NAXIS", planes > 1 ? "3" : "2", "number of array dimensions"));
        cards.add(FitsHeader.formatCard("NAXIS1", Integer.toString(width), null));
        cards.add(FitsHeader.formatCard("NAXIS2", Integer.toString(height), null));
        if (planes > 1) {
            cards.add(FitsHeader.formatCard("NAXIS3", Integer.toString(planes), null));
        }
        cards.add(FitsHeader.formatCard("EXTEND", "T", null));
        cards.add(FitsHeader.formatCard("BSCALE", "1", null));
        cards.add(FitsHeader.formatCard("BZERO", "32768", null));
        FitsHeader header = FitsHeader.of(cards);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.toBytes());
        int count = width * height * planes;
        for (int i = 0; i < count; i++) {
            int unsigned = valueAt.applyAsInt(i) & 0xffff;
            short stored = (short) (unsigned - 32768);
            out.write(stored >> 8);
            out.write(stored);
        }
        long dataBytes = (long) count * 2;
        long padding = FitsHeader.padToBlock(dataBytes) - dataBytes;
        for (long i = 0; i < padding; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    /** Noisy 14-bit "sky" shifted into 16-bit words: every value is a multiple of 4. */
    static byte[] cameraLike(int width, int height, int planes, long seed) {
        java.util.Random random = new java.util.Random(seed);
        int[] values = new int[width * height * planes];
        for (int i = 0; i < values.length; i++) {
            int x = i % width;
            int sample = 1500 + (int) (300 * Math.sin(x / 7.0)) + random.nextInt(200);
            values[i] = (sample & 0x3fff) << 2;
        }
        return plain16(width, height, planes, i -> values[i]);
    }

    static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }
}
