""" F Prime YAMCS: F Prime framing plugin for fprime-comm-bridge

Deployments built with F Prime framing (Svc.FprimeFramer and Svc.FprimeDeframer) send each packet as
DEADBEEF | U32 length | packet | CRC32. YAMCS takes Space Packets on downlink and sends TC frames on uplink, so this
plugin converts between the two. The launcher selects it for `--framing-selection fprime`.
"""
import struct

from fprime_gds.common.communication.framing import FramerDeframer, FpFramerDeframer
from fprime_gds.plugin.definitions import gds_plugin_implementation

SPACE_PACKET_HEADER_SIZE = 6
# YAMCS's UDP_TC_OUT link sends TC frames with a 5-byte primary header and a 2-byte CRC16 trailer
TC_FRAME_HEADER_SIZE = 5
TC_FRAME_TRAILER_SIZE = 2
SEQUENCE_COUNT_MODULO = 0x4000


class FprimeSpacePacketFramerDeframer(FramerDeframer):
    """ Turns F Prime frames into Space Packets for YAMCS, and YAMCS's TC frames into F Prime frames """

    def __init__(self):
        self.fprime = FpFramerDeframer()
        self.sequence_counts = {}

    def frame(self, data):
        """ Take the packets out of a YAMCS TC frame and put each one in an F Prime frame """
        packets = data[TC_FRAME_HEADER_SIZE:-TC_FRAME_TRAILER_SIZE]
        framed = b""
        # YAMCS can put several packets in one TC frame, so we step through them by each header's length field
        while len(packets) >= SPACE_PACKET_HEADER_SIZE:
            end = SPACE_PACKET_HEADER_SIZE + int.from_bytes(packets[4:6], "big") + 1
            framed += self.fprime.frame(packets[SPACE_PACKET_HEADER_SIZE:end])
            packets = packets[end:]
        return framed

    def deframe(self, data, no_copy=False):
        """ Deframe one F Prime frame and return its packet behind a Space Packet header """
        packet, data, discarded = self.fprime.deframe(data, no_copy)
        if packet is None:
            return None, data, discarded
        # YAMCS tells packets apart by APID, and F Prime uses each packet's descriptor (its first two bytes) as the APID
        apid = int.from_bytes(packet[:2], "big") & 0x7FF
        # We number each APID's packets in turn. FprimePacketPreprocessor warns when an APID's count skips, and YAMCS
        # archives only one of two packets with the same time and count.
        count = self.sequence_counts.get(apid, 0)
        self.sequence_counts[apid] = (count + 1) % SEQUENCE_COUNT_MODULO
        # 0xC000 marks an unsegmented packet
        header = struct.pack(">HHH", apid, 0xC000 | count, len(packet) - 1)
        return header + packet, data, discarded

    @classmethod
    def get_name(cls):
        """Get the name of this plugin"""
        return "fprime-space-packet"

    @classmethod
    @gds_plugin_implementation
    def register_framing_plugin(cls):
        """Register this plugin as a framing plugin"""
        return cls
