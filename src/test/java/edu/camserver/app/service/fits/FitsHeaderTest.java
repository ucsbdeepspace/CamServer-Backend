package edu.camserver.app.service.fits;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FitsHeaderTest {

    @Test
    void parsesFixedFormatValuesAndDataLength() throws IOException {
        byte[] file = TestFits.plain16(10, 4, 3, i -> i * 4);
        FitsHeader header = FitsHeader.read(new ByteArrayInputStream(file));

        assertTrue(header.isPrimary());
        assertEquals(16, header.bitpix());
        assertEquals(3, header.naxis());
        assertArrayEquals(new long[] {10, 4, 3}, header.axes());
        assertEquals(32768, header.longValue("BZERO").orElseThrow());
        assertEquals(1.0, header.doubleValue("BSCALE").orElseThrow());
        assertEquals(10 * 4 * 3 * 2, header.dataBytes());
        assertEquals(FitsHeader.BLOCK, header.paddedDataBytes());
        assertEquals(FitsHeader.BLOCK, header.byteLength());
        assertFalse(header.has("CSBITSHF"));
    }

    @Test
    void unmodifiedHeaderIsEchoedByteForByte() throws IOException {
        byte[] file = TestFits.plain16(3, 2, 1, i -> 8);
        FitsHeader header = FitsHeader.read(new ByteArrayInputStream(file));
        byte[] original = java.util.Arrays.copyOf(file, header.byteLength());
        assertArrayEquals(original, header.toBytes());
    }

    @Test
    void setAndRemoveKeepBlockPadding() throws IOException {
        FitsHeader header = FitsHeader.read(new ByteArrayInputStream(TestFits.plain16(3, 2, 1, i -> 8)));
        header.setLong("BSCALE", 4, "shifted");
        header.setLong("CSBITSHF", 2, "bits");
        assertEquals(4, header.longValue("BSCALE").orElseThrow());
        assertEquals(2, header.longValue("CSBITSHF").orElseThrow());
        byte[] bytes = header.toBytes();
        assertEquals(0, bytes.length % FitsHeader.BLOCK);
        String text = TestFits.ascii(bytes, 0, bytes.length);
        assertTrue(text.contains("BSCALE  =                    4 / shifted"));
        assertTrue(text.contains("END     "));

        assertTrue(header.remove("CSBITSHF"));
        assertFalse(header.has("CSBITSHF"));
        assertFalse(header.remove("CSBITSHF"));
    }

    @Test
    void quotedStringsAndCommentsAreParsed() {
        FitsHeader header = FitsHeader.of(List.of(
                "SIMPLE  =                    T / yes",
                "XTENSION= 'BINTABLE'           / binary table extension",
                "ZCMPTYPE= 'RICE_1  '           / compression algorithm",
                "NAXIS   =                    2 / two",
                "ZIMAGE  =                    T / extension contains compressed image",
                "SLASHY  = 'a / b'              / comment with slash"));
        assertEquals("BINTABLE", header.stringValue("XTENSION").orElseThrow());
        assertEquals("RICE_1", header.stringValue("ZCMPTYPE").orElseThrow());
        assertEquals("a / b", header.stringValue("SLASHY").orElseThrow());
        assertTrue(header.booleanValue("ZIMAGE", false));
        assertEquals(2, header.naxis());
    }

    @Test
    void rejectsNonFitsInput() {
        byte[] junk = new byte[FitsHeader.BLOCK];
        java.util.Arrays.fill(junk, (byte) ' ');
        assertThrows(IOException.class, () -> FitsHeader.read(new ByteArrayInputStream(junk)));
        assertThrows(IOException.class, () -> FitsHeader.read(new ByteArrayInputStream(new byte[10])));
    }
}
