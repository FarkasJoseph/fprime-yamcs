package com.example.myproject;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yamcs.TmPacket;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.utils.TimeEncoding;

public class FprimePacketPreprocessorTest {

    @BeforeAll
    public static void setUpYamcs() {
        TimeEncoding.setUp(); // loads the real leap second table
        EventProducerFactory.setMockup(true); // no Yamcs instance to send events to
    }

    @Test
    public void generationTimeIsFprimeTime() {
        // 2026-09-29T00:00:00.123456Z
        assertEquals("2026-09-29T00:00:00.123Z", eventGenerationTime(1790640000L, 123456));
    }

    @Test
    public void unixTimeZeroIsEpoch() {
        // Yamcs adds no leap seconds in 1970, so a fixed offset would show up here
        assertEquals("1970-01-01T00:00:00.000Z", eventGenerationTime(0, 0));
    }

    @Test
    public void secondsFrom2038RolloverAreUnsigned() {
        // 2^31 is the first F Prime time with the top bit of its U32 seconds set
        assertEquals("2038-01-19T03:14:08.000Z", eventGenerationTime(1L << 31, 0));
    }

    // F Prime on Linux stamps events with TB_WORKSTATION_TIME, Unix seconds and microseconds.
    // We build one (space packet header, packet descriptor, event id, time tag) and process it.
    private static String eventGenerationTime(long seconds, int useconds) {
        ByteBuffer buf = ByteBuffer.allocate(6 + 2 + 4 + 2 + 1 + 4 + 4);
        buf.putShort((short) 2); // APID 2, events
        buf.putShort((short) 0xC001);
        buf.putShort((short) (buf.capacity() - 7));
        buf.putShort((short) 2); // FW_PACKET_LOG
        buf.putInt(0);
        buf.putShort((short) 2); // TB_WORKSTATION_TIME
        buf.put((byte) 0);
        buf.putInt((int) seconds);
        buf.putInt(useconds);
        TmPacket packet = new FprimePacketPreprocessor("test").process(new TmPacket(0, buf.array()));
        return TimeEncoding.toString(packet.getGenerationTime());
    }
}
