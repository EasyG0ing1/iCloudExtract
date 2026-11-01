package com.simtechdata.zip;

/** Counters for one zip file. Each zip is processed by a single thread, so plain fields are fine. */
public final class ZipReport {

    public final String name;

    public int entries;
    public int directories;
    public int extracted;
    public int overwritten;
    public int skippedIdentical;
    public int skippedConflict;
    public int placeholders;
    public int folderRedirects;
    public int renamed;
    public int failed;

    public int verified;
    public int verifyFailed;
    public int verifyUnchecked;

    public boolean integrityFailed;
    public String fatal;

    /** How many errors have been echoed to the console for this zip (the log gets all of them). */
    public int errorsPrinted;

    public ZipReport(String name) {
        this.name = name;
    }

    public boolean ok() {
        return fatal == null && !integrityFailed && failed == 0 && verifyFailed == 0;
    }

    public String summary(boolean verifying) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": ");
        if (fatal != null) {
            return sb.append("FAILED - ").append(fatal).toString();
        }
        if (integrityFailed) {
            return sb.append("FAILED integrity check, nothing extracted").toString();
        }
        sb.append(entries).append(" file entries, ")
                .append(directories).append(" folders | extracted ").append(extracted);
        if (overwritten > 0) {
            sb.append(", overwritten ").append(overwritten);
        }
        if (skippedIdentical > 0) {
            sb.append(", skipped identical ").append(skippedIdentical);
        }
        if (skippedConflict > 0) {
            sb.append(", skipped conflicts ").append(skippedConflict);
        }
        if (renamed > 0) {
            sb.append(", renamed for name collisions ").append(renamed);
        }
        if (placeholders > 0) {
            sb.append(", empty placeholders dropped ").append(placeholders);
        }
        if (folderRedirects > 0) {
            sb.append(", files whose children went to -folder ").append(folderRedirects);
        }
        sb.append(", FAILED ").append(failed);
        if (verifying) {
            sb.append(" | verify: ok ").append(verified)
                    .append(", mismatched ").append(verifyFailed)
                    .append(", not checked ").append(verifyUnchecked);
        }
        return sb.toString();
    }
}
