package edu.camserver.app.live;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Splits an ISO BMFF byte stream (fragmented MP4) into top-level boxes as they arrive.
 *
 * <p>Each call to {@link #next()} blocks until one complete box has been read, so the caller can
 * forward {@code moof}/{@code mdat} pairs to viewers the moment they are complete. Boxes that
 * declare "extends to end of file" (size 0) are rejected because a live stream has no end.
 */
public final class Mp4BoxReader {

    /** One top-level box including its header bytes. */
    public record Box(String type, byte[] bytes) {
        public int size() {
            return bytes.length;
        }
    }

    private final InputStream in;
    private final int maxBoxBytes;

    public Mp4BoxReader(InputStream in, int maxBoxBytes) {
        this.in = in;
        this.maxBoxBytes = maxBoxBytes;
    }

    /**
     * Reads the next box.
     *
     * @return the box, or {@code null} when the stream ended cleanly on a box boundary
     * @throws IOException on a truncated box, an oversized box, or a socket error
     */
    public Box next() throws IOException {
        byte[] header = new byte[8];
        int read = readFully(header, 0, 8, true);
        if (read == 0) {
            return null;
        }
        if (read < 8) {
            throw new EOFException("stream ended inside a box header");
        }

        long size = Mp4Parser.u32(header, 0);
        String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
        int headerLength = 8;
        byte[] largeSize = null;
        if (size == 1) {
            largeSize = new byte[8];
            readFully(largeSize, 0, 8, false);
            size = Mp4Parser.u64(largeSize, 0);
            headerLength = 16;
        } else if (size == 0) {
            throw new IOException("box '" + type + "' extends to end of stream; send fragmented MP4"
                    + " (ffmpeg -movflags frag_keyframe+empty_moov+default_base_moof)");
        }
        if (size < headerLength) {
            throw new IOException("invalid size " + size + " for box '" + type + "'");
        }
        if (size > maxBoxBytes) {
            throw new IOException("box '" + type + "' is " + size + " bytes, above the limit of "
                    + maxBoxBytes);
        }

        byte[] bytes = new byte[(int) size];
        System.arraycopy(header, 0, bytes, 0, 8);
        if (largeSize != null) {
            System.arraycopy(largeSize, 0, bytes, 8, 8);
        }
        readFully(bytes, headerLength, (int) size - headerLength, false);
        return new Box(type, bytes);
    }

    private int readFully(byte[] target, int offset, int length, boolean allowCleanEnd) throws IOException {
        int total = 0;
        while (total < length) {
            int n = in.read(target, offset + total, length - total);
            if (n < 0) {
                if (allowCleanEnd && total == 0) {
                    return 0;
                }
                throw new EOFException("stream ended after " + total + " of " + length + " bytes");
            }
            total += n;
        }
        return total;
    }
}
