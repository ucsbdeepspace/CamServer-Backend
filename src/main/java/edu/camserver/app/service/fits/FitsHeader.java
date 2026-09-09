package edu.camserver.app.service.fits;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * One FITS header: the 80-character cards of a single HDU, without the END card.
 *
 * <p>Just enough of the standard for the archive: reading a header off a stream, looking up
 * numeric keywords, replacing or removing cards, and writing the header back out padded to the
 * 2880-byte block size. Values are parsed from the fixed-format layout the FITS writers in use
 * (astropy, CFITSIO) produce.
 */
public final class FitsHeader {
    public static final int BLOCK = 2880;
    public static final int CARD = 80;
    private static final int CARDS_PER_BLOCK = BLOCK / CARD;
    private static final String END = "END";

    private final List<String> cards;
    private final int byteLength;
    private byte[] raw;

    private FitsHeader(List<String> cards, int byteLength, byte[] raw) {
        this.cards = cards;
        this.byteLength = byteLength;
        this.raw = raw;
    }

    /** Header with the given cards (each padded/truncated to 80 characters), END excluded. */
    public static FitsHeader of(List<String> cards) {
        List<String> copy = new ArrayList<>(cards.size());
        for (String card : cards) {
            copy.add(pad(card));
        }
        return new FitsHeader(copy, paddedLength(copy.size() + 1), null);
    }

    /**
     * Reads whole 2880-byte blocks until the END card. Throws {@link EOFException} when the stream
     * ends first and {@link IOException} when the bytes do not look like a FITS header.
     */
    public static FitsHeader read(InputStream in) throws IOException {
        List<String> cards = new ArrayList<>(36);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(BLOCK);
        byte[] block = new byte[BLOCK];
        int blocks = 0;
        while (true) {
            readFully(in, block);
            raw.writeBytes(block);
            blocks++;
            if (blocks == 1) {
                String first = new String(block, 0, 8, StandardCharsets.US_ASCII);
                if (!first.equals("SIMPLE  ") && !first.equals("XTENSION")) {
                    throw new IOException("Not a FITS header: first keyword is '" + first.trim() + "'");
                }
            }
            for (int i = 0; i < CARDS_PER_BLOCK; i++) {
                String card = new String(block, i * CARD, CARD, StandardCharsets.US_ASCII);
                if (card.startsWith(END) && card.substring(3).isBlank()) {
                    return new FitsHeader(cards, blocks * BLOCK, raw.toByteArray());
                }
                cards.add(card);
            }
            if (blocks > 10_000) {
                throw new IOException("FITS header has no END card within " + blocks + " blocks");
            }
        }
    }

    /** Number of bytes this header occupies on disk, including END and block padding. */
    public int byteLength() {
        return byteLength;
    }

    public boolean isPrimary() {
        return !cards.isEmpty() && cards.get(0).startsWith("SIMPLE  ");
    }

    public List<String> cards() {
        return List.copyOf(cards);
    }

    public boolean has(String keyword) {
        return indexOf(keyword) >= 0;
    }

    /** The value field as written (comment stripped, string quotes kept), if the keyword exists. */
    public Optional<String> rawValue(String keyword) {
        int index = indexOf(keyword);
        return index < 0 ? Optional.empty() : Optional.of(valueField(cards.get(index)));
    }

    public Optional<String> stringValue(String keyword) {
        return rawValue(keyword).map(FitsHeader::unquote);
    }

    public OptionalLong longValue(String keyword) {
        Optional<String> raw = rawValue(keyword);
        if (raw.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            String text = raw.get();
            // Some writers emit integers as "4.0"; accept them when they are whole.
            if (text.contains(".") || text.contains("E") || text.contains("e")) {
                double d = Double.parseDouble(text);
                if (d == Math.rint(d)) {
                    return OptionalLong.of((long) d);
                }
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public OptionalDouble doubleValue(String keyword) {
        Optional<String> raw = rawValue(keyword);
        if (raw.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(raw.get().replace('D', 'E')));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    public boolean booleanValue(String keyword, boolean fallback) {
        Optional<String> raw = rawValue(keyword);
        if (raw.isEmpty()) {
            return fallback;
        }
        String v = raw.get();
        return v.equals("T") || (v.equals("F") ? false : fallback);
    }

    public long bitpix() {
        return longValue("BITPIX").orElse(0);
    }

    public int naxis() {
        return (int) longValue("NAXIS").orElse(0);
    }

    public long[] axes() {
        int n = naxis();
        long[] axes = new long[n];
        for (int i = 0; i < n; i++) {
            axes[i] = longValue("NAXIS" + (i + 1)).orElse(0);
        }
        return axes;
    }

    /** Length in bytes of the data unit that follows this header (before block padding). */
    public long dataBytes() {
        int n = naxis();
        if (n == 0) {
            return 0;
        }
        long elements = 1;
        for (long axis : axes()) {
            elements = Math.multiplyExact(elements, axis);
        }
        long pcount = longValue("PCOUNT").orElse(0);
        long gcount = longValue("GCOUNT").orElse(1);
        long bytesPerElement = Math.abs(bitpix()) / 8;
        return Math.multiplyExact(bytesPerElement, Math.multiplyExact(gcount, Math.addExact(pcount, elements)));
    }

    public long paddedDataBytes() {
        return padToBlock(dataBytes());
    }

    public static long padToBlock(long bytes) {
        long rem = bytes % BLOCK;
        return rem == 0 ? bytes : bytes + (BLOCK - rem);
    }

    /** Replaces the first card with this keyword in place, or appends a new one. */
    public void setLong(String keyword, long value, String comment) {
        raw = null;
        setCard(formatCard(keyword, Long.toString(value), comment));
    }

    private void setCard(String card) {
        String keyword = card.substring(0, 8);
        int index = indexOf(keyword.trim());
        if (index >= 0) {
            cards.set(index, card);
        } else {
            cards.add(card);
        }
    }

    /** Removes every card with this keyword. Returns whether anything was removed. */
    public boolean remove(String keyword) {
        String padded = padKeyword(keyword);
        boolean removed = cards.removeIf(card -> card.startsWith(padded));
        if (removed) {
            raw = null;
        }
        return removed;
    }

    /**
     * Serialises the cards plus END, padded with spaces to a multiple of 2880 bytes. A header
     * read from a stream and not modified since is returned exactly as it was read.
     */
    public byte[] toBytes() {
        if (raw != null) {
            return raw.clone();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(paddedLength(cards.size() + 1));
        for (String card : cards) {
            out.writeBytes(card.getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes(pad(END).getBytes(StandardCharsets.US_ASCII));
        int written = (cards.size() + 1) * CARD;
        int padding = paddedLength(cards.size() + 1) - written;
        for (int i = 0; i < padding; i++) {
            out.write(' ');
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    private int indexOf(String keyword) {
        String padded = padKeyword(keyword);
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).startsWith(padded)) {
                return i;
            }
        }
        return -1;
    }

    private static String padKeyword(String keyword) {
        String upper = keyword.trim().toUpperCase(Locale.ROOT);
        if (upper.length() > 8) {
            throw new IllegalArgumentException("FITS keyword longer than 8 characters: " + keyword);
        }
        return String.format(Locale.ROOT, "%-8s", upper);
    }

    private static int paddedLength(int cardCount) {
        int blocks = (cardCount + CARDS_PER_BLOCK - 1) / CARDS_PER_BLOCK;
        return Math.max(1, blocks) * BLOCK;
    }

    private static String pad(String card) {
        if (card.length() >= CARD) {
            return card.substring(0, CARD);
        }
        StringBuilder sb = new StringBuilder(CARD).append(card);
        while (sb.length() < CARD) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** Fixed-format card: keyword, "= ", value right-aligned in 20 columns, optional comment. */
    public static String formatCard(String keyword, String value, String comment) {
        String card = padKeyword(keyword) + "= " + String.format(Locale.ROOT, "%20s", value);
        if (comment != null && !comment.isBlank()) {
            card = card + " / " + comment;
        }
        return pad(card);
    }

    /** Value text of a card: everything after "= " up to the comment separator, trimmed. */
    static String valueField(String card) {
        if (card.length() < 10 || card.charAt(8) != '=' || card.charAt(9) != ' ') {
            return "";
        }
        String rest = card.substring(10);
        int i = 0;
        while (i < rest.length() && rest.charAt(i) == ' ') {
            i++;
        }
        if (i < rest.length() && rest.charAt(i) == '\'') {
            // quoted string; '' is an escaped quote
            int j = i + 1;
            while (j < rest.length()) {
                if (rest.charAt(j) == '\'') {
                    if (j + 1 < rest.length() && rest.charAt(j + 1) == '\'') {
                        j += 2;
                        continue;
                    }
                    break;
                }
                j++;
            }
            return rest.substring(i, Math.min(rest.length(), j + 1)).trim();
        }
        int slash = rest.indexOf('/');
        String value = slash < 0 ? rest : rest.substring(0, slash);
        return value.trim();
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1).replace("''", "'").stripTrailing();
        }
        return raw;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) {
                if (offset == 0) {
                    throw new EOFException("Stream ended before a FITS header block");
                }
                throw new EOFException("Truncated FITS header block");
            }
            offset += n;
        }
    }
}
