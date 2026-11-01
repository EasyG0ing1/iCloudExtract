package com.icloudextract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "icloudextract",
        version = "icloudextract 1.0.0",
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
                "Extracts the multi-part zip files from Apple's Data and Privacy export.",
                "",
                "Unlike unzip, a bad entry never stops the archive: each entry is handled on its own and "
                        + "failures are logged. Names that are not valid UTF-8 are read as Windows-1252. When a "
                        + "path is both a file and a folder, the file is kept and its contents go to a folder "
                        + "with -folder added to the name.",
                ""},
        footer = {
                "",
                "Logs are written to the folder the program was started from:",
                "  iCloudExtract.log            everything, including every error",
                "  iCloudExtract-conflicts.log  skipped, overwritten and redirected files",
                "",
                "Examples:",
                "  icloudextract -source ~/Downloads -destination ~/Extracted",
                "  icloudextract -source ~/Downloads -destination ~/iCloudDrive -consolidate -verify crc",
                "  icloudextract -destination out -overwrite newest -threads 4 -checkzip"})
public final class Main implements java.util.concurrent.Callable<Integer> {

    @Option(names = {"-destination", "--destination"}, defaultValue = "iCloudExtract", paramLabel = "<folder>",
            description = {"Folder to extract into (created if it does not exist).",
                    "Default: a folder named iCloudExtract inside the current folder."})
    private String destination;

    @Option(names = {"-source", "--source"}, paramLabel = "<folder|zip>", defaultValue = ".",
            description = {"A folder containing the numbered zip files, or one zip file.",
                    "Gaps in the numbering are reported as a warning, not an error. Default: the current folder."})
    private String source;

    @Option(names = {"-verify", "--verify"}, arity = "0..1", fallbackValue = "size", paramLabel = "[crc]",
            description = {"After extracting each zip, check that every file is there and that its size matches the",
                    "size recorded in the zip. Add 'crc' (-verify crc) to also recompute each file's CRC-32 and",
                    "compare it with the zip's recorded value."})
    private String verify;

    @Option(names = {"-consolidate", "--consolidate"},
            description = {"Leave out the folder that mirrors each zip's file name (for example",
                    "'iCloud Drive Part 3 of 18') so the contents of all the zips merge into one tree.",
                    "Existing folders are merged into. If a file of the same size already exists it is skipped;",
                    "use -overwrite to replace it."})
    private boolean consolidate;

    @Option(names = {"-overwrite", "--overwrite"}, arity = "0..1", fallbackValue = "always", paramLabel = "[newest]",
            description = {"Replace files that already exist with a different version (every replacement is logged).",
                    "Add 'newest' (-overwrite newest) to replace a file only when the zip's copy is newer.",
                    "A file that already exists with the same size is skipped."})
    private String overwrite;

    @Option(names = {"-checkzip", "--checkzip"},
            description = "Test each zip file's integrity (all entries, CRC-32) before extracting it. "
                    + "A zip that fails is not extracted.")
    private boolean checkZip;

    @Option(names = {"-yes", "--yes"},
            description = {"Answer yes to every question, so the program can run unattended. Without this, when more",
                    "than one zip file is going to be extracted the program lists them and asks whether to proceed,",
                    "and with -consolidate it also asks before moving existing zip-named folders up into the",
                    "destination."})
    private boolean yes;

    @Option(names = {"-threads", "--threads"}, defaultValue = "1", paramLabel = "<n>",
            description = "How many zip files to extract at the same time. Default: ${DEFAULT-VALUE}.")
    private int threads;

    @Option(names = {"-help", "--help"}, usageHelp = true, description = "Show this help and exit.")
    private boolean help;

    @Option(names = {"-version", "--version"}, versionHelp = true, description = "Show the version and exit.")
    private boolean version;

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setPosixClusteredShortOptionsAllowed(false);
        cli.setCaseInsensitiveEnumValuesAllowed(true);
        System.exit(cli.execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Settings settings;
        try {
            settings = settings();
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        Path sourcePath = Path.of(source).toAbsolutePath().normalize();
        ZipSourceResolver.Resolved resolved;
        try {
            resolved = ZipSourceResolver.resolve(sourcePath);
        } catch (ZipSourceResolver.SourceException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        for (String warning : resolved.warnings()) {
            System.err.println("Warning: " + warning);
        }
        if (resolved.zips().size() > 1 && !yes) {
            Boolean answer = confirm(settings, resolved.zips());
            if (answer == null) {
                System.err.println("No answer was given, so nothing was extracted. "
                        + "Use -yes to run without being asked.");
                return 1;
            }
            if (!answer) {
                System.out.println("Cancelled. Nothing was extracted.");
                return 0;
            }
        }

        List<Path> mirrors = settings.consolidate()
                ? existingMirrorFolders(settings.destination(), resolved.zips()) : List.of();
        if (!mirrors.isEmpty() && !yes) {
            Boolean answer = confirmMoveUp(settings, mirrors);
            if (answer == null) {
                System.err.println("No answer was given, so nothing was changed. "
                        + "Use -yes to run without being asked.");
                return 1;
            }
            if (!answer) {
                System.out.println("Stopped. Nothing was moved or extracted.");
                return 0;
            }
        }

        Path cwd = Path.of("").toAbsolutePath();
        try (RunLog log = new RunLog(cwd)) {
            return run(settings, resolved, sourcePath, log, mirrors);
        }
    }

    /** Folders in the destination that are named after the zip files about to be consolidated. */
    private static List<Path> existingMirrorFolders(Path destination, List<Path> zips) {
        List<Path> found = new ArrayList<>();
        for (Path zip : zips) {
            String name = zip.getFileName().toString();
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                name = name.substring(0, name.length() - 4);
            }
            Path folder = destination.resolve(name);
            if (Files.isDirectory(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                found.add(folder);
            }
        }
        return found;
    }

    private static Boolean confirmMoveUp(Settings settings, List<Path> mirrors) throws IOException {
        java.util.Set<String> inside = new java.util.TreeSet<>();
        for (Path mirror : mirrors) {
            try (java.util.stream.Stream<Path> listing = Files.list(mirror)) {
                listing.forEach(p -> inside.add(p.getFileName().toString()));
            }
        }
        System.out.println();
        System.out.println("The destination already contains folders named after zip files you are about to "
                + "consolidate:");
        for (Path mirror : mirrors) {
            System.out.println("  " + mirror.getFileName());
        }
        System.out.println();
        System.out.println("To consolidate, what they contain " + inside + " has to be moved up into");
        System.out.println("  " + settings.destination());
        System.out.println("and merged there. Nothing is deleted or overwritten. Files that are already extracted");
        System.out.println("are not extracted again: each one is compared with the zip by path, size and CRC-32.");
        System.out.println();
        System.out.print("Move them up and continue? [y/N] ");
        System.out.flush();
        return readYesNo();
    }

    /** Reads an answer from standard input: true for y/yes, false for anything else, null at end of input. */
    private static Boolean readYesNo() throws IOException {
        java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.Charset.defaultCharset()));
        String line = in.readLine();
        if (line == null) {
            System.out.println();
            return null;
        }
        String answer = line.trim().toLowerCase(Locale.ROOT);
        return answer.equals("y") || answer.equals("yes");
    }

    /** Shows the zip files that are about to be extracted. Returns null when there is no one to answer. */
    private static Boolean confirm(Settings settings, List<Path> zips) throws IOException {
        System.out.println();
        if (settings.consolidate()) {
            System.out.println(zips.size() + " zip files will be consolidated into " + settings.destination()
                    + " (their contents merge into one folder tree):");
        } else {
            System.out.println(zips.size() + " zip files will be extracted into " + settings.destination()
                    + " (each into its own folder):");
        }
        int width = String.valueOf(zips.size()).length();
        for (int i = 0; i < zips.size(); i++) {
            System.out.printf("  %" + width + "d. %s%n", i + 1, zips.get(i).getFileName());
        }
        System.out.println();
        System.out.print("Proceed? [y/N] ");
        System.out.flush();
        return readYesNo();
    }

    private Settings settings() {
        if (destination == null || destination.isBlank()) {
            destination = "iCloudExtract";
        }
        if (threads < 1) {
            throw new IllegalArgumentException("-threads must be at least 1");
        }
        Settings.VerifyMode verifyMode = Settings.VerifyMode.NONE;
        if (verify != null) {
            switch (verify.toLowerCase(Locale.ROOT)) {
                case "size" -> verifyMode = Settings.VerifyMode.SIZE;
                case "crc" -> verifyMode = Settings.VerifyMode.CRC;
                default -> throw new IllegalArgumentException(
                        "-verify accepts no value (size check) or 'crc', not '" + verify + "'");
            }
        }
        Settings.OverwriteMode overwriteMode = Settings.OverwriteMode.NONE;
        if (overwrite != null) {
            switch (overwrite.toLowerCase(Locale.ROOT)) {
                case "always" -> overwriteMode = Settings.OverwriteMode.ALWAYS;
                case "newest" -> overwriteMode = Settings.OverwriteMode.NEWEST;
                default -> throw new IllegalArgumentException(
                        "-overwrite accepts no value or 'newest', not '" + overwrite + "'");
            }
        }
        Path dest = Path.of(destination).toAbsolutePath().normalize();
        if (Files.exists(dest) && !Files.isDirectory(dest)) {
            throw new IllegalArgumentException("-destination exists and is not a folder: " + dest);
        }
        return new Settings(dest, verifyMode, consolidate, overwriteMode, checkZip, threads);
    }

    private int run(Settings settings, ZipSourceResolver.Resolved resolved, Path sourcePath, RunLog log,
                    List<Path> mirrors) throws IOException, InterruptedException {
        Files.createDirectories(settings.destination());
        List<Path> zips = resolved.zips();

        log.info("Run started. source=" + sourcePath + " destination=" + settings.destination()
                + " zips=" + zips.size() + " threads=" + settings.threads()
                + " consolidate=" + settings.consolidate() + " overwrite=" + settings.overwrite()
                + " verify=" + settings.verify() + " checkzip=" + settings.checkZip());
        System.out.println("Extracting " + zips.size() + " zip file(s) from " + sourcePath);
        System.out.println("Destination: " + settings.destination());
        for (String warning : resolved.warnings()) {
            log.warn(warning);
        }

        if (settings.threads() > 1 && settings.overwrite() == Settings.OverwriteMode.ALWAYS) {
            String notice = "NOTICE: " + settings.threads() + " zip files will be extracted at the same time and "
                    + "-overwrite is on. If the same file path appears in more than one zip, there is no guarantee "
                    + "which version will end up written. Use '-overwrite newest', or -threads 1, for a predictable "
                    + "result.";
            System.err.println(notice);
            log.warn(notice);
        }

        boolean verifying = settings.verify() != Settings.VerifyMode.NONE;
        int total = zips.size();
        List<Future<ZipReport>> futures = new ArrayList<>();
        boolean crc = settings.verify() == Settings.VerifyMode.CRC;
        Progress progress = new Progress(Progress.onTerminal());
        int failedZips = 0;
        int entries = 0;
        int extracted = 0;
        int overwritten = 0;
        int identical = 0;
        int conflicts = 0;
        int renamed = 0;
        int failed = 0;
        int verifyBad = 0;
        try {
            progress.start();
            if (!mirrors.isEmpty()) {
                FolderMerger.Stats moved = new FolderMerger.Stats();
                for (Path mirror : mirrors) {
                    String mirrorName = mirror.getFileName().toString();
                    progress.line("Moving files for consolidation: " + mirrorName + " -> " + settings.destination());
                    progress.activity("Moving files out of " + mirrorName);
                    new FolderMerger(settings.destination(), log, mirrorName, settings.overwrite(), crc)
                            .mergeContents(mirror, settings.destination(), moved);
                }
                String message = "Moved " + moved.moved + " item(s) up out of " + mirrors.size()
                        + " zip-named folder(s)"
                        + (moved.duplicatesRemoved + moved.renamed + moved.overwritten + moved.conflictsLeft > 0
                        ? "; " + moved.duplicatesRemoved + " identical duplicate(s) removed, "
                        + moved.renamed + " renamed, " + moved.overwritten + " overwritten, "
                        + moved.conflictsLeft + " different file(s) left in place (see "
                        + RunLog.CONFLICT_LOG_NAME + ")"
                        : "")
                        + ".";
                progress.line(message);
                log.info(message);
                progress.activity("");
            }

            ZipExtractor extractor = new ZipExtractor(settings, log, progress);
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(settings.threads(), total));
            for (int i = 0; i < total; i++) {
                final int number = i + 1;
                final Path zip = zips.get(i);
                futures.add(pool.submit(() -> {
                    String name = zip.getFileName().toString();
                    progress.begin(name);
                    progress.line("[" + number + "/" + total + "] Unzipping file " + name
                            + (settings.consolidate() ? " into " + settings.destination() : ""));
                    log.info("Extracting " + name);
                    ZipReport report = extractor.extract(zip);
                    progress.end(name);
                    progress.line("[" + number + "/" + total + "] " + report.summary(verifying));
                    log.info(report.summary(verifying));
                    return report;
                }));
            }
            pool.shutdown();

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
        System.out.println("Elapsed time: " + progress.elapsed());
        log.info("Run finished. failedZips=" + failedZips + " failed=" + failed + " verifyMismatches=" + verifyBad);
        if (failedZips > 0) {
            System.err.println(failedZips + " zip file(s) had problems; see the log for the full list.");
            return 1;
        }
        return 0;
    }
}
