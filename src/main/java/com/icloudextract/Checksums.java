package com.icloudextract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

final class Checksums {

    private Checksums() {
    }

    static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                crc.update(buffer, 0, n);
            }
        }
        return crc.getValue();
    }
}
