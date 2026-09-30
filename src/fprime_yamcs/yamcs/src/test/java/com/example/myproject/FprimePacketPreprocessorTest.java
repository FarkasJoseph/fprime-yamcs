package com.example.myproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.utils.TimeEncoding;

public class FprimePacketPreprocessorTest {

    // FPrimeTime seen live from the BigData deployment: TB_WORKSTATION_TIME, 2026-09-30T23:05:16.668064Z
    private static final int TB_WORKSTATION_TIME = 2;
    private static final int TB_SC_TIME = 3;
    private static final long LIVE_SECONDS = 1790809516L;
    private static final long LIVE_USECONDS = 668064L;
    private static final String LIVE_TIME = "2026-09-30T23:05:16.668Z";

    private long receptionTime;

    @BeforeAll
    public static void setUpYamcs() {
        TimeEncoding.setUp(); // loads the real leap second table
        EventProducerFactory.setMockup(true);
    }

    @BeforeEach
    public void setUp() {
        EventProducerFactory.getMockupQueue().clear();
        receptionTime = TimeEncoding.parse("2026-09-30T23:10:00.000Z");
    }

    @Test
    public void unixTimeMatchesFprimeClockOnEveryTimedApid() {
        // APID and id width: channels (4), events (4), packetized telemetry (2)
        int[][] layouts = { { 1, 4 }, { 2, 4 }, { 4, 2 } };
        for (int[] layout : layouts) {
            TmPacket packet = process(packet(layout[0], layout[1], TB_WORKSTATION_TIME, LIVE_SECONDS, LIVE_USECONDS));
            assertEquals(LIVE_TIME, TimeEncoding.toString(packet.getGenerationTime()), "APID " + layout[0]);
            assertFalse(isLocalGenTime(packet));
        }
    }

    @Test
    public void timeBaseNoneIsUnix() {
        TmPacket packet = process(event(0, LIVE_SECONDS, LIVE_USECONDS));
        assertEquals(LIVE_TIME, TimeEncoding.toString(packet.getGenerationTime()));
    }

    @Test
    public void secondsAreUnsigned() {
        TmPacket packet = process(event(TB_WORKSTATION_TIME, 1L << 31, 0));
        assertEquals("2038-01-19T03:14:08.000Z", TimeEncoding.toString(packet.getGenerationTime()));
    }

    @Test
    public void otherTimeBaseUsesReceptionTime() {
        TmPacket packet = process(event(TB_SC_TIME, LIVE_SECONDS, LIVE_USECONDS));
        assertEquals(receptionTime, packet.getGenerationTime());
        assertTrue(isLocalGenTime(packet));
        assertTrue(warned("UNKNOWN_TIME_BASE"));
    }

    @Test
    public void unixTimeBasesIsConfigurable() {
        FprimePacketPreprocessor preprocessor = new FprimePacketPreprocessor("test",
                YConfiguration.wrap(Map.of("unixTimeBases", List.of(TB_SC_TIME))));

        TmPacket sc = preprocessor.process(event(TB_SC_TIME, LIVE_SECONDS, LIVE_USECONDS));
        assertEquals(LIVE_TIME, TimeEncoding.toString(sc.getGenerationTime()));

        TmPacket workstation = preprocessor.process(event(TB_WORKSTATION_TIME, LIVE_SECONDS, LIVE_USECONDS));
        assertEquals(receptionTime, workstation.getGenerationTime());
        assertTrue(isLocalGenTime(workstation));
    }

    @Test
    public void invalidUsecondsUseReceptionTime() {
        TmPacket packet = process(event(TB_WORKSTATION_TIME, LIVE_SECONDS, 1_000_000));
        assertEquals(receptionTime, packet.getGenerationTime());
        assertTrue(isLocalGenTime(packet));
        assertTrue(warned("INVALID_TIME"));
    }

    @Test
    public void packetWithoutTimeTagUsesReceptionTime() {
        // APID 3 carries F Prime file packets, which have no time tag
        TmPacket packet = process(packet(3, 4, TB_WORKSTATION_TIME, LIVE_SECONDS, LIVE_USECONDS));
        assertEquals(receptionTime, packet.getGenerationTime());
        assertTrue(isLocalGenTime(packet));
        assertFalse(warned("UNKNOWN_TIME_BASE") || warned("INVALID_TIME") || warned("SHORT_PACKET"));
    }

    @Test
    public void shortTimedPacketUsesReceptionTime() {
        byte[] full = event(TB_WORKSTATION_TIME, LIVE_SECONDS, LIVE_USECONDS).getPacket();
        TmPacket packet = process(new TmPacket(receptionTime, Arrays.copyOf(full, full.length - 1)));
        assertEquals(receptionTime, packet.getGenerationTime());
        assertTrue(warned("SHORT_PACKET"));
    }

    private static TmPacket process(TmPacket packet) {
        return new FprimePacketPreprocessor("test").process(packet);
    }

    private TmPacket event(int timeBase, long seconds, long useconds) {
        return packet(2, 4, timeBase, seconds, useconds);
    }

    // Space packet header, packet descriptor, id, then the F Prime time tag
    private TmPacket packet(int apid, int idSize, int timeBase, long seconds, long useconds) {
        ByteBuffer buf = ByteBuffer.allocate(6 + 2 + idSize + 2 + 1 + 4 + 4);
        buf.putShort((short) apid);
        buf.putShort((short) 0xC001);
        buf.putShort((short) (buf.capacity() - 7));
        buf.putShort((short) 0);
        buf.put(new byte[idSize]);
        buf.putShort((short) timeBase);
        buf.put((byte) 0);
        buf.putInt((int) seconds);
        buf.putInt((int) useconds);
        return new TmPacket(receptionTime, buf.array());
    }

    private static boolean isLocalGenTime(TmPacket packet) {
        return (packet.getStatus() & TmPacket.STATUS_MASK_LOCAL_GEN_TIME) != 0;
    }

    private static boolean warned(String type) {
        return EventProducerFactory.getMockupQueue().stream().anyMatch(e -> type.equals(e.getType()));
    }
}
