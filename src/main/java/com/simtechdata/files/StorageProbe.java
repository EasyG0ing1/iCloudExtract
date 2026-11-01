package com.simtechdata.files;

import com.simtechdata.enums.StorageKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Makes a best effort to find out whether a path is on a solid state drive or a spinning hard drive. Java has no API
 * for that, so it asks the operating system: {@code diskutil} on macOS, {@code /sys/class/block} on Linux and
 * PowerShell on Windows. Anything it cannot tell, such as a network share or a USB adapter that hides the drive type,
 * comes back as {@link StorageKind#UNKNOWN}; this class never throws and never writes anything.
 */
public final class StorageProbe {

    private static final long COMMAND_SECONDS = 8;
    private static final Pattern MAC_SOLID_STATE = Pattern.compile("<key>SolidState</key>\\s*<(true|false)\\s*/>");

    private StorageProbe() {
    }

    /** The kind of drive under {@code location} (the closest part of it that exists). */
    public static StorageKind detect(Path location) {
        try {
            Path p = location.toAbsolutePath().normalize();
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            if (p == null) {
                return StorageKind.UNKNOWN;
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                return detectMac(p);
            }
            if (os.contains("win")) {
                return detectWindows(p);
            }
            if (os.contains("linux")) {
                return detectLinux(p);
            }
        } catch (IOException | RuntimeException e) {
            // fall through: unknown
        }
        return StorageKind.UNKNOWN;
    }

    // ---- macOS ---------------------------------------------------------------------------------------------

    private static StorageKind detectMac(Path path) throws IOException {
        // diskutil wants a device or a mount point, not any path, so ask Java which device the path is on
        String device = Files.getFileStore(path).name();
        String info = run(List.of("diskutil", "info", "-plist", device == null ? path.toString() : device));
        StorageKind kind = macSolidState(info);
        if (kind != StorageKind.UNKNOWN) {
            return kind;
        }
        // An APFS volume does not say; the physical store it lives on does. For other volumes try the whole disk.
        String next = macString(info, "APFSPhysicalStore");
        if (next == null) {
            next = macString(info, "ParentWholeDisk");
        }
        for (int hop = 0; next != null && hop < 3; hop++) {
            String more = run(List.of("diskutil", "info", "-plist", next));
            kind = macSolidState(more);
            if (kind != StorageKind.UNKNOWN) {
                return kind;
            }
            String parent = macString(more, "ParentWholeDisk");
            next = parent != null && !parent.equals(next) ? parent : null;
        }
        return StorageKind.UNKNOWN;
    }

    /** SSD or HDD from the {@code SolidState} entry of a {@code diskutil info -plist} answer; UNKNOWN if it has none. */
    static StorageKind macSolidState(String plist) {
        if (plist == null) {
            return StorageKind.UNKNOWN;
        }
        Matcher m = MAC_SOLID_STATE.matcher(plist);
        if (!m.find()) {
            return StorageKind.UNKNOWN;
        }
        return m.group(1).equals("true") ? StorageKind.SSD : StorageKind.HDD;
    }

    /** The first {@code <string>} value of {@code key} in a plist, or null. */
    static String macString(String plist, String key) {
        if (plist == null) {
            return null;
        }
        Matcher m = Pattern.compile("<key>" + Pattern.quote(key) + "</key>\\s*<string>([^<]+)</string>").matcher(plist);
        return m.find() ? m.group(1).trim() : null;
    }

    // ---- Linux ---------------------------------------------------------------------------------------------

    private static StorageKind detectLinux(Path path) throws IOException {
        String device = Files.getFileStore(path).name();
        if (device == null || !device.startsWith("/dev/")) {
            return StorageKind.UNKNOWN;
        }
        return linuxDevice(Path.of("/sys/class/block"), device.substring("/dev/".length()), 0);
    }

    /** Looks at {@code <sysBlock>/<name>}: the disk's own flag, else the whole disk's, else the disks under it (LVM, RAID). */
    static StorageKind linuxDevice(Path sysBlock, String name, int depth) {
        try {
            // device-mapper names such as "mapper/vg-root" are not entries of /sys/class/block
            Path entry = sysBlock.resolve(Path.of(name).getFileName().toString());
            if (!Files.exists(entry) || depth > 3) {
                return StorageKind.UNKNOWN;
            }
            Path real = entry.toRealPath();
            for (Path candidate : new Path[] {real, real.getParent()}) {
                if (candidate == null) {
                    continue;
                }
                Path flag = candidate.resolve("queue").resolve("rotational");
                if (Files.isRegularFile(flag)) {
                    return linuxRotational(Files.readString(flag, StandardCharsets.UTF_8));
                }
            }
            Path slaves = real.resolve("slaves");
            if (Files.isDirectory(slaves)) {
                boolean allSsd = true;
                boolean any = false;
                try (Stream<Path> list = Files.list(slaves)) {
                    for (Path slave : (Iterable<Path>) list::iterator) {
                        any = true;
                        StorageKind k = linuxDevice(sysBlock, slave.getFileName().toString(), depth + 1);
                        if (k == StorageKind.HDD) {
                            return StorageKind.HDD;
                        }
                        allSsd &= k == StorageKind.SSD;
                    }
                }
                return any && allSsd ? StorageKind.SSD : StorageKind.UNKNOWN;
            }
        } catch (IOException | RuntimeException e) {
            // unknown
        }
        return StorageKind.UNKNOWN;
    }

    /** {@code 0} in {@code queue/rotational} means no moving parts. */
    static StorageKind linuxRotational(String text) {
        String t = text == null ? "" : text.trim();
        return t.equals("0") ? StorageKind.SSD : t.equals("1") ? StorageKind.HDD : StorageKind.UNKNOWN;
    }

    // ---- Windows -------------------------------------------------------------------------------------------

    private static StorageKind detectWindows(Path path) {
        Path root = path.getRoot();
        String r = root == null ? "" : root.toString();
        if (r.length() < 2 || r.charAt(1) != ':' || !Character.isLetter(r.charAt(0))) {
            return StorageKind.UNKNOWN; // a network (UNC) path
        }
        char letter = Character.toUpperCase(r.charAt(0));
        String script = "(Get-PhysicalDisk | Where-Object { $_.DeviceId -eq (Get-Partition -DriveLetter " + letter
                + ").DiskNumber }).MediaType";
        return windowsMediaType(run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script)));
    }

    /** SSD or HDD from the {@code MediaType} PowerShell prints ("SSD", "HDD", "Unspecified"). */
    static StorageKind windowsMediaType(String text) {
        String t = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        return t.contains("SSD") ? StorageKind.SSD : t.contains("HDD") ? StorageKind.HDD : StorageKind.UNKNOWN;
    }

    // ---- helper --------------------------------------------------------------------------------------------

    /** Runs a command and returns what it printed; null when it fails or takes more than a few seconds. */
    private static String run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            Process p = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return null;
                }
            });
            String text = output.get(COMMAND_SECONDS, TimeUnit.SECONDS);
            process.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS);
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
