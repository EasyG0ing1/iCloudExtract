            for (Future<ZipReport> future : futures) {
                try {
                    ZipReport r = future.get();
                    entries += r.entries;
                    extracted += r.extracted;
                    overwritten += r.overwritten;
                    identical += r.skippedIdentical;
                    conflicts += r.skippedConflict;
                    renamed += r.renamed;
                    failed += r.failed;
                    verifyBad += r.verifyFailed;
                    if (!r.ok()) {
                        failedZips++;
                    }
                } catch (ExecutionException e) {
                    failedZips++;
                    log.error("Unexpected failure: " + e.getCause());
                    progress.error("Unexpected failure: " + e.getCause());
                }
            }
        } finally {
            progress.close();
        }

        System.out.println();
        System.out.println("Done. " + entries + " file entries | extracted " + extracted
                + " | overwritten " + overwritten + " | skipped identical " + identical
                + " | skipped conflicts " + conflicts + " | renamed " + renamed + " | failed " + failed
                + (verifying ? " | verify mismatches " + verifyBad : ""));
        System.out.println("Logs: " + log.runLogFile() + (Files.exists(log.conflictLogFile())
                ? " and " + log.conflictLogFile() : ""));
        log.info("Run finished. failedZips=" + failedZips + " failed=" + failed + " verifyMismatches=" + verifyBad);
        if (failedZips > 0) {
            System.err.println(failedZips + " zip file(s) had problems; see the log for the full list.");
            return 1;
        }
        return 0;
    }
}
