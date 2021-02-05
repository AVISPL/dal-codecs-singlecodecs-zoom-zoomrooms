/*
 * Copyright (c) 2015-2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.management.zoom.rooms;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
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

import static org.junit.Assert.fail;

public class ZoomRoomsCommunicatorTest {
    ZoomRoomsCommunicator zoomRoomsCommunicator;

    @BeforeEach
    void setUp () throws Exception {
        zoomRoomsCommunicator = new ZoomRoomsCommunicator();
        zoomRoomsCommunicator.setHost("***REMOVED***4");
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
        dialDevice.setDialString("2754909175.013196@zoomcrc.com");
        zoomRoomsCommunicator.dial(dialDevice);
        Thread.sleep(5000);
        zoomRoomsCommunicator.getMultipleStatistics();
        Assert.assertEquals(CallStatus.CallStatusState.Connected, zoomRoomsCommunicator.retrieveCallStatus("").getCallStatusState());
    }

    @Test
    public void testDisconnect() throws Exception {
        zoomRoomsCommunicator.hangup("");
        Assert.assertEquals(CallStatus.CallStatusState.Disconnected, zoomRoomsCommunicator.retrieveCallStatus("").getCallStatusState());
    }

    @Test
    public void startPMI() throws Exception {
        DialDevice dialDevice = new DialDevice();
        dialDevice.setDialString("2149695280@zoomcrc.com");
        dialDevice.setProtocol(Protocol.SIP);
        zoomRoomsCommunicator.dial(dialDevice);
        // The same as dial, but testing "start" method instead of "join"
    }

    @Test
    public void testMute() throws Exception {
        if(zoomRoomsCommunicator.retrieveCallStatus("").getCallStatusState().equals(CallStatus.CallStatusState.Disconnected)){
            fail("Has to be joined to the meeting");
        }
        if(MuteStatus.Muted.equals(zoomRoomsCommunicator.retrieveMuteStatus())){
            zoomRoomsCommunicator.unmute();
            Assert.assertEquals(MuteStatus.Unmuted, zoomRoomsCommunicator.retrieveMuteStatus());
        } else {
            zoomRoomsCommunicator.mute();
            Assert.assertEquals(MuteStatus.Muted, zoomRoomsCommunicator.retrieveMuteStatus());
        }
    }

    @Test
    public void testMuteWithControls() throws Exception {
        if(zoomRoomsCommunicator.retrieveCallStatus("").getCallStatusState().equals(CallStatus.CallStatusState.Disconnected)){
            fail("Has to be joined to the meeting");
        }
        ControllableProperty muteCommand = new ControllableProperty();
        muteCommand.setProperty("CallControls#MicrophoneMute");
        if(MuteStatus.Muted.equals(zoomRoomsCommunicator.retrieveMuteStatus())){
            muteCommand.setValue(0);
            zoomRoomsCommunicator.controlProperty(muteCommand);
            Assert.assertEquals(MuteStatus.Unmuted, zoomRoomsCommunicator.retrieveMuteStatus());
        } else {
            muteCommand.setValue(1);
            zoomRoomsCommunicator.controlProperty(muteCommand);
            Assert.assertEquals(MuteStatus.Muted, zoomRoomsCommunicator.retrieveMuteStatus());
        }
    }

    @Test
    public void testCameraMute() throws Exception {
        if(zoomRoomsCommunicator.retrieveCallStatus("").getCallStatusState().equals(CallStatus.CallStatusState.Disconnected)){
            fail("Has to be joined to the meeting");
        }
        List<Statistics> stats = zoomRoomsCommunicator.getMultipleStatistics();
        AdvancedControllableProperty videoCameraMuteControl = ((ExtendedStatistics) stats.get(1)).getControllableProperties().stream().
                filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("Call Control#Video Camera Mute")).findFirst().get();

        ControllableProperty muteCommand = new ControllableProperty();
        muteCommand.setProperty("CallControls#VideoCameraMute");
        if(videoCameraMuteControl.getValue().equals(1)){
            muteCommand.setValue(0);
            zoomRoomsCommunicator.controlProperty(muteCommand);
            stats = zoomRoomsCommunicator.getMultipleStatistics();
            videoCameraMuteControl = ((ExtendedStatistics) stats.get(1)).getControllableProperties().stream().
                    filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("Call Control#Video Camera Mute")).findFirst().get();

            Assert.assertEquals(0, videoCameraMuteControl.getValue());
        } else {
            muteCommand.setValue(1);
            zoomRoomsCommunicator.controlProperty(muteCommand);

            stats = zoomRoomsCommunicator.getMultipleStatistics();
            videoCameraMuteControl = ((ExtendedStatistics) stats.get(1)).getControllableProperties().stream().
                    filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("Call Control#Video Camera Mute")).findFirst().get();

            Assert.assertEquals(1, videoCameraMuteControl.getValue());
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
        dialDevice.setDialString("1234567890.012345@somesip.com");
        dialDevice.setProtocol(Protocol.H323);
        String response = zoomRoomsCommunicator.dial(dialDevice);

        Assert.assertNull(response);
    }

    @Test
    public void cameraMove() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("CameraControls#MoveUp");
        zoomRoomsCommunicator.controlProperty(controllableProperty);
        // Nothing to assert here, only created for manual testing
    }

}
