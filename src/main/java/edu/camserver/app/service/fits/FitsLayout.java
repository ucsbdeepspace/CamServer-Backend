package edu.camserver.app.service.fits;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which HDUs of a FITS file hold an image: the first plain (uncompressed) image HDU and the first
 * tile-compressed one ({@code ZIMAGE = T}), each as an HDU index where 0 is the primary array,
 * or -1 when absent.
 */
public record FitsLayout(FitsHeader primary, int plainImageHdu, int compressedImageHdu, int hduCount) {

    public static FitsLayout inspect(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            FitsHeader primary = null;
            int plain = -1;
            int compressed = -1;
            int index = 0;
            while (true) {
                FitsHeader header;
                try {
                    header = FitsHeader.read(in);
                } catch (EOFException e) {
                    if (index == 0) {
                        throw e;
                    }
                    break;
                } catch (IOException e) {
                    if (index == 0) {
                        throw e;
                    }
                    break; // trailing bytes that are not another HDU
                }
                if (index == 0) {
                    primary = header;
                }
                if (header.booleanValue("ZIMAGE", false)) {
                    if (compressed < 0) {
                        compressed = index;
                    }
                } else if (isPlainImage(header)) {
                    if (plain < 0) {
                        plain = index;
                    }
                }
                try {
                    in.skipNBytes(header.paddedDataBytes());
                } catch (EOFException e) {
                    index++;
                    break; // truncated data unit: imcopy/fpack will report it if it matters
                }
                index++;
            }
            return new FitsLayout(primary, plain, compressed, index);
        }
    }

    public boolean hasImage() {
        return plainImageHdu >= 0 || compressedImageHdu >= 0;
    }

    private static boolean isPlainImage(FitsHeader header) {
        String extension = header.stringValue("XTENSION").orElse("");
        boolean table = extension.equalsIgnoreCase("BINTABLE") || extension.equalsIgnoreCase("TABLE");
        return !table && header.naxis() >= 2 && header.dataBytes() > 0;
    }
}
