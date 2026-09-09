package edu.camserver.app.model.archive;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Which files an archive job should touch. At least one selector must be set, or
 * {@code all} must be true, so that an empty request body cannot sweep the whole archive.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ArchiveSelection {
    /** Explicit file names (with or without the .gz suffix). */
    private List<String> names;
    /** File-name prefix, typically the camera id, e.g. {@code QHY5III678C}. */
    private String prefix;
    /** Frames captured more than this many days ago. */
    private Integer olderThanDays;
    /** Frames captured strictly before this time (camera local time, as in the file name). */
    private LocalDateTime before;
    /** Frames captured at or after this time. */
    private LocalDateTime after;
    /** File extensions to consider; defaults to the configured FITS extensions. */
    private List<String> extensions;
    /** Include 0-byte files (normally skipped: nothing to gain). */
    private boolean includeEmpty;
    /** Stop after this many files. */
    private Integer limit;
    /** Select every matching file even when no other selector is given. */
    private boolean all;
    /** Report what would happen without touching any file. */
    private boolean dryRun;

    public boolean hasSelector() {
        return all
                || (names != null && !names.isEmpty())
                || (prefix != null && !prefix.isBlank())
                || olderThanDays != null
                || before != null
                || after != null;
    }
}
