package com.example.myproject;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class FprimeCommandPostprocessorTest {

    @Test
    public void eachPostprocessorCountsFromZero() {
        FprimeCommandPostprocessor first = new FprimeCommandPostprocessor("first");
        FprimeCommandPostprocessor second = new FprimeCommandPostprocessor("second");
        byte[] header = new byte[6]; // space packet header for APID 0
        assertEquals(0, first.fillSeqCount(header));
        assertEquals(0, second.fillSeqCount(header));
    }
}
