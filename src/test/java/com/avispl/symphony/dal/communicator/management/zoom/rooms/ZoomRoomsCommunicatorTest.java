package com.avispl.symphony.dal.communicator.management.zoom.rooms;

import com.avispl.symphony.api.dal.dto.control.Protocol;
import com.avispl.symphony.api.dal.dto.control.call.CallStatus;
import com.avispl.symphony.api.dal.dto.control.call.DialDevice;
import com.avispl.symphony.api.dal.dto.control.call.MuteStatus;
import com.avispl.symphony.api.dal.dto.monitor.EndpointStatistics;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.Assert;

import java.util.List;
import java.util.logging.Level;

import static org.junit.Assert.fail;

public class ZoomRoomsCommunicatorTest {
    ZoomRoomsCommunicator zoomRoomsCommunicator;

    @BeforeEach
    void setUp () throws Exception {
        zoomRoomsCommunicator = new ZoomRoomsCommunicator();
        zoomRoomsCommunicator.setHost("172.31.254.213");
        zoomRoomsCommunicator.setLogin("zoom");
        zoomRoomsCommunicator.setPort(2244);
        zoomRoomsCommunicator.setPassword("***REMOVED***");
        zoomRoomsCommunicator.init();
    }

    @Test
    public void testStatistics() throws Exception {
        List<Statistics> stats = zoomRoomsCommunicator.getMultipleStatistics();

        Assert.assertFalse(((EndpointStatistics) stats.get(0)).isInCall());
    }

    @Test
    public void testDial() throws Exception {
        List<Statistics> stats = zoomRoomsCommunicator.getMultipleStatistics();
        DialDevice dialDevice = new DialDevice();
        dialDevice.setDialString("2754909175.013196");
        dialDevice.setProtocol(Protocol.H323);
        zoomRoomsCommunicator.dial(dialDevice);
        zoomRoomsCommunicator.getMultipleStatistics();
        Assert.assertEquals(((ExtendedStatistics) stats.get(0)).getStatistics().get("Call Status"), "in meeting");
    }

    @Test
    public void testMute() throws Exception {
        List<Statistics> stats = zoomRoomsCommunicator.getMultipleStatistics();
        if(!((ExtendedStatistics)stats.get(0)).getStatistics().get("Call Status").equals("in meeting")){
            fail("Has to be joined to the meeting");
        }
        if(zoomRoomsCommunicator.retrieveMuteStatus().equals(MuteStatus.Muted)){
            zoomRoomsCommunicator.unmute();
            Assert.assertEquals(MuteStatus.Unmuted, zoomRoomsCommunicator.retrieveMuteStatus());
        } else {
            zoomRoomsCommunicator.mute();
            Assert.assertEquals(MuteStatus.Muted, zoomRoomsCommunicator.retrieveMuteStatus());
        }
    }

    @Test
    public void testHangup() throws Exception {
        List<Statistics> stats = zoomRoomsCommunicator.getMultipleStatistics();
        if(!((EndpointStatistics)stats.get(0)).isInCall()){
            fail("Has to be joined to the meeting");
        }
        Assert.assertEquals(CallStatus.CallStatusState.Connected, zoomRoomsCommunicator.retrieveCallStatus("").getCallStatusState());
        zoomRoomsCommunicator.hangup("");
        Assert.assertEquals(CallStatus.CallStatusState.Disconnected, zoomRoomsCommunicator.retrieveCallStatus("").getCallStatusState());
    }

    @Test
    public void testDialInvalidAndRecover() throws Exception {
        List<Statistics> stats = zoomRoomsCommunicator.getMultipleStatistics();
        DialDevice dialDevice = new DialDevice();
        dialDevice.setDialString("1234567890.012345@zoomcrc.com");
        dialDevice.setProtocol(Protocol.H323);
        String response = zoomRoomsCommunicator.dial(dialDevice);

        Assert.assertNull(response);
    }
}
