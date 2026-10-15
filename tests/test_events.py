"""Tests for publishing decoded F Prime events to YAMCS"""

from unittest.mock import Mock

from fprime_gds.common.data_types.event_data import EventData
from fprime_gds.common.models.serialize.time_type import TimeType
from fprime_gds.common.templates.event_template import EventTemplate
from fprime_gds.common.utils.event_severity import EventSeverity

from fprime_yamcs.events.processor import FPrimeEventProcessor


def test_event_message_is_display_text():
    processor = FPrimeEventProcessor.__new__(FPrimeEventProcessor)
    processor.yamcs_client = Mock()
    processor.yamcs_instance = "test"
    template = EventTemplate(
        0x1000, "NoOpReceived", "cmdDisp", [], EventSeverity.ACTIVITY_HI, "Received a NO-OP command"
    )

    processor._publish_event(EventData((), TimeType(), template), None)

    assert processor.yamcs_client.send_event.call_args.kwargs["message"] == "Received a NO-OP command"
