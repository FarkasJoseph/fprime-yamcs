"""Tests for the framing plugin that converts between F Prime frames and the packets and TC frames YAMCS uses"""

from fprime_yamcs.framing import FprimeSpacePacketFramerDeframer

# A SystemRes1 telemetry packet (descriptor 4) from a BigData deployment, and the F Prime frame around it:
# DEADBEEF | U32 length | packet | CRC32
PACKET = bytes.fromhex("000400050002006ad00710000bb1570000000000f6caa40000000000683958000000003eeda9b4000000003c7022ec")
FRAME = bytes.fromhex("deadbeef0000002f") + PACKET + bytes.fromhex("d862f7c3")
# A systemResources.CPU channel (descriptor 1) from a Ref deployment, in its F Prime frame
CHANNEL = bytes.fromhex("0001100230040002006ad006770004f0003f889927")
CHANNEL_FRAME = bytes.fromhex("deadbeef00000015") + CHANNEL + bytes.fromhex("33d0e181")

# CMD_NO_OP_STRING("hello") as YAMCS's UDP_TC_OUT link sends it: TC header | Space Packet | CRC16
TC_FRAME = bytes.fromhex("20440419001000c000000c000001000001000568656c6c6fa893")
# The same command in an F Prime frame, as Svc.FprimeDeframer expects it
FRAMED_COMMAND = bytes.fromhex("deadbeef0000000d000001000001000568656c6c6f46757b43")


def test_deframe_puts_each_packet_behind_a_space_packet_header():
    framing = FprimeSpacePacketFramerDeframer()
    stream = b"\x00\x11" + FRAME + CHANNEL_FRAME + FRAME + FRAME[:5]
    packets, remaining, discarded = framing.deframe_all(stream, no_copy=False)
    # Each header has the APID from the descriptor, the unsegmented flag, that APID's own sequence count, and the
    # packet length minus one
    assert packets == [
        bytes.fromhex("0004c000002e") + PACKET,
        bytes.fromhex("0001c0000014") + CHANNEL,
        bytes.fromhex("0004c001002e") + PACKET,
    ]
    assert remaining == FRAME[:5]
    assert discarded == b"\x00\x11"


def test_frame_unwraps_each_packet_in_a_tc_frame():
    framing = FprimeSpacePacketFramerDeframer()
    assert framing.frame(TC_FRAME) == FRAMED_COMMAND
    # CMD_NO_OP and CMD_NO_OP_STRING sharing one TC frame
    two_commands = bytes.fromhex("20440425001000c01300050000010000001000c012000c000001000001000568656c6c6fe7fa")
    assert framing.frame(two_commands) == bytes.fromhex("deadbeef00000006000001000000181e8960") + FRAMED_COMMAND
