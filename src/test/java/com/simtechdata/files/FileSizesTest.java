package com.simtechdata.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FileSizesTest {

    @Test
    void smallSizesAreWholeBytes() {
        assertEquals("0 B", FileSizes.format(0));
        assertEquals("512 B", FileSizes.format(512));
        assertEquals("999 B", FileSizes.format(999));
        assertEquals("0 B", FileSizes.format(-5));
    }

    @Test
    void largerSizesUseTwoDecimalsAndTheLargestUnitThatFits() {
        assertEquals("1.00 KB", FileSizes.format(1_000));
        assertEquals("1.50 KB", FileSizes.format(1_500));
        assertEquals("768.00 MB", FileSizes.format(768_000_000L));
        assertEquals("2.30 GB", FileSizes.format(2_300_000_000L));
        assertEquals("23.40 GB", FileSizes.format(23_400_000_000L));
        assertEquals("83.42 GB", FileSizes.format(83_421_998_123L));
        assertEquals("1.20 TB", FileSizes.format(1_200_000_000_000L));
    }

    @Test
    void roundingNeverShowsAThousandOfAUnit() {
        assertEquals("1.00 MB", FileSizes.format(999_999));
        assertEquals("1.00 GB", FileSizes.format(999_999_999));
    }

    @Test
    void exactCountHasThousandsSeparators() {
        assertEquals("83,421,998,123 bytes", FileSizes.exact(83_421_998_123L));
        assertEquals("0 bytes", FileSizes.exact(0));
    }
}
