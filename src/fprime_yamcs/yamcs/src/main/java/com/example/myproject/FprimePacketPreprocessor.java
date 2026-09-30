package com.example.myproject;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.AbstractPacketPreprocessor;
import org.yamcs.utils.TimeEncoding;

/**
 * Component capable of modifying packet binary received from a link, before
 * passing it further into Yamcs.
 * <p>
 * A single instance of this class is created, scoped to the link udp-in.
 * <p>
 * This is specified in the configuration file yamcs.myproject.yaml:
 * 
 * <pre>
 * ...
 * dataLinks:
 *   - name: udp-in
 *     class: org.yamcs.tctm.UdpTmDataLink
 *     stream: tm_realtime
 *     host: localhost
 *     port: 10015
 *     packetPreprocessorClassName: com.example.myproject.FprimePacketPreprocessor
 * ...
 * </pre>
 */
public class FprimePacketPreprocessor extends AbstractPacketPreprocessor {

    private Map<Integer, AtomicInteger> seqCounts = new HashMap<>();

    // Widths and offsets
    private static final int SPACE_PACKET_HEADER_LEN = 6;
    private static final int FwPacketDescriptorType_SIZE = 2;
    private static final int FwTlmPacketizeIdType_SIZE = 2;
    private static final int FwTimeBaseStoreType_SIZE = 2;
    private static final int FwTimeContextStoreType_SIZE = 1;
    private static final int FwEventIdType_SIZE = 4;
    private static final int FwChanIdType_SIZE = 4;

    private static final int TLM_TIME_TAG_OFFSET = SPACE_PACKET_HEADER_LEN + FwPacketDescriptorType_SIZE
            + FwTlmPacketizeIdType_SIZE + FwTimeBaseStoreType_SIZE + FwTimeContextStoreType_SIZE;

    private static final int EVENT_TIME_TAG_OFFSET = SPACE_PACKET_HEADER_LEN + FwPacketDescriptorType_SIZE
            + FwEventIdType_SIZE + FwTimeBaseStoreType_SIZE + FwTimeContextStoreType_SIZE;

    private static final int CHAN_ID_OFFSET = SPACE_PACKET_HEADER_LEN + FwPacketDescriptorType_SIZE;

    private static final int PKT_ID_OFFSET = SPACE_PACKET_HEADER_LEN + FwPacketDescriptorType_SIZE;

    private static final int CHAN_TIME_TAG_OFFSET = SPACE_PACKET_HEADER_LEN + FwPacketDescriptorType_SIZE
            + FwChanIdType_SIZE + FwTimeBaseStoreType_SIZE + FwTimeContextStoreType_SIZE;

    // APIDs
    private static final int APID_TLM_CHAN = 1; // default F' APID for telemetry channels
    private static final int APID_EVENT = 2; // default F' APID for events
    private static final int APID_TLM_PKT = 4; // default F' APID for telemetry packets

    // Telemetry channel ids whose packets are marked "do not archive": they remain
    // available on the realtime processor but are skipped by the XtceTmRecorder and
    // thus never reach the tm table nor the (backfilled) parameter archive.
    private final Set<Long> doNotArchiveChannelIds = new HashSet<>();

    // Packetized-telemetry (Svc.TlmPacketizer, APID 4) packet ids treated the same way.
    private final Set<Integer> doNotArchivePacketIds = new HashSet<>();

    // F Prime time bases whose seconds count from the Unix epoch (TB_WORKSTATION_TIME).
    // Packets with any other time base get the reception time as generation time.
    private final Set<Integer> unixTimeBases = new HashSet<>(Set.of(2));

    // Constructor used when this preprocessor is used without YAML configuration
    public FprimePacketPreprocessor(String yamcsInstance) {
        this(yamcsInstance, YConfiguration.emptyConfig());
    }

    // Constructor used when this preprocessor is used with YAML configuration
    // (packetPreprocessorClassArgs)
    public FprimePacketPreprocessor(String yamcsInstance, YConfiguration config) {
        super(yamcsInstance, config);
        if (config.containsKey("doNotArchiveChannelIds")) {
            for (Object id : config.getList("doNotArchiveChannelIds")) {
                doNotArchiveChannelIds.add(((Number) id).longValue());
            }
        }
        if (config.containsKey("doNotArchivePacketIds")) {
            for (Object id : config.getList("doNotArchivePacketIds")) {
                doNotArchivePacketIds.add(((Number) id).intValue());
            }
        }
        if (config.containsKey("unixTimeBases")) {
            unixTimeBases.clear();
            for (Object base : config.getList("unixTimeBases")) {
                unixTimeBases.add(((Number) base).intValue());
            }
        }
    }

    @Override
    public TmPacket process(TmPacket packet) {

        byte[] bytes = packet.getPacket();

        if (bytes.length < 6) { // Expect at least the length of CCSDS primary header
            eventProducer.sendWarning("SHORT_PACKET",
                    "Short packet received, length: " + bytes.length + "; minimum required length is 6 bytes.");

            // If we return null, the packet is dropped.
            return null;
        }

        // Verify continuity for a given APID based on the CCSDS sequence counter
        int apidseqcount = ByteBuffer.wrap(bytes).getInt(0);
        int apid = (apidseqcount >> 16) & 0x07FF;
        int seq = (apidseqcount) & 0x3FFF;
        AtomicInteger ai = seqCounts.computeIfAbsent(apid, k -> new AtomicInteger());
        int oldseq = ai.getAndSet(seq);

        if (((seq - oldseq) & 0x3FFF) != 1) {
            eventProducer.sendWarning("SEQ_COUNT_JUMP",
                    "Sequence count jump for APID: " + apid + " old seq: " + oldseq + " newseq: " + seq);
        }

        int time_tag_offset = -1; // stays -1 for packets without an F Prime time tag
        // Find time tags depending on APID
        if (apid == APID_EVENT) {
            time_tag_offset = EVENT_TIME_TAG_OFFSET;
        } else if (apid == APID_TLM_PKT) {
            time_tag_offset = TLM_TIME_TAG_OFFSET;
            if (!doNotArchivePacketIds.isEmpty() && bytes.length >= PKT_ID_OFFSET + FwTlmPacketizeIdType_SIZE) {
                int packetId = ByteBuffer.wrap(bytes).getShort(PKT_ID_OFFSET) & 0xFFFF;
                if (doNotArchivePacketIds.contains(packetId)) {
                    packet.setDoNotArchive();
                }
            }
        } else if (apid == APID_TLM_CHAN) {
            time_tag_offset = CHAN_TIME_TAG_OFFSET;
            if (!doNotArchiveChannelIds.isEmpty() && bytes.length >= CHAN_ID_OFFSET + FwChanIdType_SIZE) {
                long channelId = ByteBuffer.wrap(bytes).getInt(CHAN_ID_OFFSET) & 0xFFFFFFFFL;
                if (doNotArchiveChannelIds.contains(channelId)) {
                    packet.setDoNotArchive();
                }
            }
        }
        setGenerationTime(packet, bytes, time_tag_offset);

        // Use the full 32-bits, so that both APID and the count are included.
        // Yamcs uses this attribute to uniquely identify the packet (together with the
        // gentime)
        packet.setSequenceCount(apidseqcount);

        return packet;
    }

    // Reads the F Prime time tag (base, context, seconds, useconds) whose seconds field is
    // at timeTagOffset. Packets without a usable Unix time tag keep their reception time.
    private void setGenerationTime(TmPacket packet, byte[] bytes, int timeTagOffset) {
        if (timeTagOffset < 0) {
            setLocalGenerationTime(packet);
            return;
        }
        if (bytes.length < timeTagOffset + 8) {
            eventProducer.sendWarning("SHORT_PACKET",
                    "Packet too short for its F Prime time tag, length: " + bytes.length);
            setLocalGenerationTime(packet);
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int timeBase = buf.getShort(timeTagOffset - FwTimeContextStoreType_SIZE - FwTimeBaseStoreType_SIZE)
                & 0xFFFF;
        long timeSec = buf.getInt(timeTagOffset) & 0xFFFFFFFFL;
        long timeUsec = buf.getInt(timeTagOffset + 4) & 0xFFFFFFFFL;
        if (!unixTimeBases.contains(timeBase)) {
            eventProducer.sendWarning("UNKNOWN_TIME_BASE",
                    "F Prime time base " + timeBase + " is not in unixTimeBases; using reception time");
            setLocalGenerationTime(packet);
        } else if (timeUsec >= 1_000_000) {
            eventProducer.sendWarning("INVALID_TIME",
                    "F Prime time has useconds " + timeUsec + " >= 1000000; using reception time");
            setLocalGenerationTime(packet);
        } else {
            // Yamcs instants count leap seconds, Unix time does not; see
            // https://docs.yamcs.org/yamcs-server-manual/general/time/
            packet.setGenerationTime(TimeEncoding.fromUnixMillisec(timeSec * 1000 + timeUsec / 1000));
        }
    }

    private static void setLocalGenerationTime(TmPacket packet) {
        packet.setGenerationTime(packet.getReceptionTime());
        packet.setLocalGenTimeFlag();
    }

}