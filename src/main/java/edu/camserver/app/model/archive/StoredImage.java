package edu.camserver.app.model.archive;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Where an image file actually lives on disk.
 *
 * @param logicalName the name clients ask for, e.g. {@code frame.fits} (never ends in .gz or .fz)
 * @param path        the file that holds the bytes: {@code frame.fits}, {@code frame.fits.gz} or
 *                    {@code frame.fits.fz}
 * @param format      how {@code path} is stored
 * @param size        size in bytes of {@code path}
 */
public record StoredImage(String logicalName, Path path, Format format, long size) {

    /** On-disk forms of an archived image. */
    public enum Format {
        /** The file as uploaded. */
        PLAIN(""),
        /** Whole file gzip-compressed. */
        GZIP(".gz"),
        /** FITS tile compression (Rice) via fpack; the file is itself a valid FITS file. */
        RICE(".fz");

        private final String suffix;

        Format(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }

        public boolean compressed() {
            return this != PLAIN;
        }

        /** Format implied by a file name's suffix. */
        public static Format ofFileName(String fileName) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(GZIP.suffix)) {
                return GZIP;
            }
            if (lower.endsWith(RICE.suffix)) {
                return RICE;
            }
            return PLAIN;
        }

        /** The name without this format's suffix. */
        public String logicalName(String fileName) {
            return this == PLAIN ? fileName : fileName.substring(0, fileName.length() - suffix.length());
        }
    }

    public boolean compressed() {
        return format.compressed();
    }

    /** Kept for callers that only know about the gzip form. */
    public boolean gzipped() {
        return format == Format.GZIP;
    }
}
