package com.genymobile.scrcpy.control;

import java.io.ByteArrayInputStream;

public final class PanelProtocolTest {
    public static void main(String[] args) throws Exception {
        ControlMessageReader reader = new ControlMessageReader(new ByteArrayInputStream(new byte[] {126, 127, 10, 1}));
        if (reader.read().getType() != ControlMessage.TYPE_PANEL_HEARTBEAT) {
            throw new AssertionError("Heartbeat not recognized");
        }
        if (reader.read().getType() != ControlMessage.TYPE_REQUEST_KEYFRAME) {
            throw new AssertionError("Keyframe request not recognized");
        }
        ControlMessage power = reader.read();
        if (power.getType() != ControlMessage.TYPE_SET_DISPLAY_POWER || !power.getOn()) {
            throw new AssertionError("Heartbeat consumed the following panel command");
        }
        try {
            new ControlMessageReader(new ByteArrayInputStream(new byte[] {125})).read();
            throw new AssertionError("Unknown message accepted");
        } catch (ControlProtocolException expected) {
            // Unknown extension remains a protocol error.
        }
        System.out.println("PanelProtocol: heartbeat, following command and rejection passed");
    }
}
