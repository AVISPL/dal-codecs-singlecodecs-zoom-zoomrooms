/*
 * Copyright (c) 2015-2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.management.zoom.rooms;

import com.avispl.symphony.api.common.error.InvalidArgumentException;
import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.control.call.CallController;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.control.call.CallStatus;
import com.avispl.symphony.api.dal.dto.control.call.DialDevice;
import com.avispl.symphony.api.dal.dto.control.call.MuteStatus;
import com.avispl.symphony.api.dal.dto.control.call.PopupMessage;
import com.avispl.symphony.api.dal.dto.monitor.*;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.SshCommunicator;
import com.avispl.symphony.dal.util.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An SshCommunicator-based adapter to provide communication with ZoomRooms software
 * Monitoring features:
 * - Call status
 * - Audio input mute status
 * - Video camera feed mute status
 * - General Zoom Rooms information (Room Name, Version, Personal Meeting Number, Active Meeting Number, Account Email)
 * - Audio Input/Output settings
 * - Video Camera settings
 * - Video Camera position
 * Controlling features:
 * - Connect to a meeting
 * - Disconnect from the meeting
 * - Mute Zoom Rooms audio input
 * - Unmute Zoom Rooms audio input
 * - Mute Zoom Rooms camera feed
 * - Unmute Zoom Rooms camera feed
 */
public class ZoomRoomsCommunicator extends SshCommunicator implements CallController, Monitorable, Controller {

    private static final String ZCOMMAND_DIAL_START = "zcommand dial start meetingNumber:%s";
    private static final String ZCOMMAND_DIAL_JOIN = "zcommand dial join meetingNumber:%s";
    private static final String ZCOMMAND_CALL_LEAVE = "zcommand call leave\r";
    private static final String ZCOMMAND_CALL_DISCONNECT = "zcommand call disconnect\r";
    private static final String ZCOMMAND_CALL_STATUS = "zstatus call status\r";
    private static final String ZCOMMAND_MUTE_STATUS = "zconfiguration call microphone mute\r";
    private static final String ZCOMMAND_CAMERA_MUTE_STATUS = "zconfiguration call camera mute\r";
    private static final String ZCOMMAND_MUTE = "zconfiguration call microphone mute: %s\r";
    private static final String ZCOMMAND_CAMERA_MUTE = "zconfiguration call camera mute: %s\r";
    private static final String ZSTATUS_AUDIO_INPUT_LINE = "zstatus audio input line\r";
    private static final String ZSTATUS_AUDIO_OUTPUT_LINE = "zstatus audio output line\r";
    private static final String ZSTATUS_CAMERA_LINE = "zstatus video camera line\r";
    private static final String ZSTATUS_SYSTEM_UNIT = "zstatus systemunit\r";
    private static final String ZCOMMAND_CALL_INFO = "zcommand call info\r";

    private static final String ON = "on";
    private static final String OFF = "off";

    private static final String IN_MEETING = "in meeting";
    private static final String CONNECTING_MEETING = "connecting meeting";

    private final ReentrantLock controlOperationsLock = new ReentrantLock();

    private boolean isHost = false;

    /**
     * ZoomRoomsCommunicator instantiation
     * Providing a list of success/error for login and other commands
     * The further error handling is done on per-command basis, since
     * failure conditions are considered in a bit more advanced way
     * than checking the end of response only, which may differ also.
     */
    public ZoomRoomsCommunicator() {
        setCommandSuccessList(Arrays.asList("** end\r\n\n", "OK\r\n\n"));
        setLoginSuccessList(Arrays.asList("\r\n** end\r\n\n", "*r Login successful\r\nOK\r\n\n"));
        setLoginErrorList(Collections.singletonList("Permission denied, please try again.\n"));
        setCommandErrorList(Arrays.asList("*e Connection rejected\r\n\n", "ERROR\r\n\n"));
    }

    @Override
    protected void internalInit() throws Exception {
        super.internalInit();
    }

    @Override
    public void setPassword(String password) {
        super.setPassword(password);
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        EndpointStatistics endpointStatistics = new EndpointStatistics();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        List<AdvancedControllableProperty> controls = new ArrayList<>();
        refreshSshConnection();

        String callStatus = getCallStatus();
        endpointStatistics.setInCall(callStatus.equals(IN_MEETING));

        Map<String, String> statistics = new HashMap<>();
        getExtendedStatus(statistics);
        if (endpointStatistics.isInCall()) {
            String meetingId = getMeetingId();
            boolean microphoneMuted = getMuteStatus();
            AudioChannelStats audioChannelStats = new AudioChannelStats();
            audioChannelStats.setMuteTx(microphoneMuted);
            endpointStatistics.setAudioChannelStats(audioChannelStats);
            CallStats callStats = new CallStats();
            callStats.setCallId(meetingId);
            endpointStatistics.setCallStats(callStats);

            // Exposing these in case if the dial was issued directly from the device page
            statistics.put("Call Control#Microphone Mute", "");
            controls.add(createSwitch("Call Control#Microphone Mute", retrieveMuteStatus().equals(MuteStatus.Muted) ? 1 : 0));
            statistics.put("Call Control#Video Camera Mute", "");
            controls.add(createSwitch("Call Control#Video Camera Mute", retrieveCameraMuteStatus() ? 1 : 0));

            statistics.put("Video Camera#Move Up", "");
            controls.add(createButton("Video Camera#Move Up", "Up", "Up", 0));
            statistics.put("Video Camera#Move Down", "");
            controls.add(createButton("Video Camera#Move Down", "Down", "Down", 0));
            statistics.put("Video Camera#Move Left", "");
            controls.add(createButton("Video Camera#Move Left", "Left", "Left", 0));
            statistics.put("Video Camera#Move Right", "");
            controls.add(createButton("Video Camera#Move Right", "Right", "Right", 0));

            statistics.put("Meeting Number (Active)", meetingId);
        } else {
            statistics.remove("Meeting Number (Active)");
            statistics.remove("Call Control#Microphone Mute");
            statistics.remove("Call Control#Video Camera Mute");
            statistics.remove("Video Camera#Move Up");
            statistics.remove("Video Camera#Move Down");
            statistics.remove("Video Camera#Move Left");
            statistics.remove("Video Camera#Move Right");
        }

        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControllableProperties(controls);
        return Arrays.asList(endpointStatistics, extendedStatistics);
    }

    /**
     * Instantiate Text controllable property
     *
     * @param name         name of the property
     * @param label        default button label
     * @param labelPressed button label when is pressed
     * @param gracePeriod  period to pause monitoring statistics for
     * @return instance of AdvancedControllableProperty with AdvancedControllableProperty.Button as type
     */
    private AdvancedControllableProperty createButton(String name, String label, String labelPressed, long gracePeriod) {
        AdvancedControllableProperty.Button button = new AdvancedControllableProperty.Button();
        button.setLabel(label);
        button.setLabelPressed(labelPressed);
        button.setGracePeriod(gracePeriod);

        return new AdvancedControllableProperty(name, new Date(), button, "");
    }

    /**
     * Create a switch
     *
     * @param name   name of the switch
     * @param status initial switch state (0|1)
     * @return AdvancedControllableProperty button instance
     */
    private AdvancedControllableProperty createSwitch(String name, int status) {
        AdvancedControllableProperty.Switch toggle = new AdvancedControllableProperty.Switch();
        toggle.setLabelOff(OFF);
        toggle.setLabelOn(ON);

        AdvancedControllableProperty advancedControllableProperty = new AdvancedControllableProperty();
        advancedControllableProperty.setName(name);
        advancedControllableProperty.setValue(status);
        advancedControllableProperty.setType(toggle);
        advancedControllableProperty.setTimestamp(new Date());

        return advancedControllableProperty;
    }

    /**
     * Get extended status data for Zoom Rooms instance.
     * In order to verify the response as valid - corresponding strings are provided.
     *
     * @param statistics map to save data to
     * @throws Exception if an error occurred during communication
     */
    private void getExtendedStatus(Map<String, String> statistics) throws Exception {
        String audioInputLine = execute(ZSTATUS_AUDIO_INPUT_LINE, "*s Audio Input Line");
        String audioOutputLine = execute(ZSTATUS_AUDIO_OUTPUT_LINE, "*s Audio Output Line");
        String systemUnit = execute(ZSTATUS_SYSTEM_UNIT, "*s SystemUnit");

        Map<String, String> lineParameters = parseZoomRoomsProperties(audioInputLine, "*s Audio Input Line");
        lineParameters.putAll(parseZoomRoomsProperties(audioOutputLine, "*s Audio Output Line"));
        lineParameters.putAll(retrieveCameraLineParameters());

        Map<String, String> systemUnitParameters = parseZoomRoomsProperties(systemUnit, "*s SystemUnit");

        lineParameters.forEach((key, value) -> {
            if (key.endsWith("Name") || key.endsWith(" Selected")) {
                if (key.startsWith("Audio")) {
                    statistics.put("Audio Settings#" + key, value);
                } else if (key.startsWith("Video Camera")) {
                    statistics.put("Video Camera Settings#" + key, value);
                }
            }
        });

        systemUnitParameters.forEach((key, value) -> {
            String keyValue = key.replace("SystemUnit ", "").replace("room_info", "");
            switch (keyValue) {
                case "room_version":
                    statistics.put("Zoom Rooms Version", value);
                    break;
                case "meeting_number":
                    statistics.put("Meeting Number (Personal)", value);
                    break;
                case "account_email":
                    statistics.put("Account Email", value);
                    break;
                case "room_name":
                    statistics.put("Room Name", value);
                    break;
                case "platform":
                    statistics.put("Platform", value);
                    break;
            }
        });
    }

    /**
     * Get map of parameters describing the currently connected cameras,
     * including information like selected/non-selected, name, id, alias etc
     *
     * @return {@link Map} containing parameters mapped out of the string feed like:
     *
     *      *s Video Camera Line 1 id: 00#8&2cc2822b&0&0000#{65e8773d-8f56-11d0-a3b9-00a0c9223196}\global
     *      *s Video Camera Line 1 Name: Logi Rally Camera
     *      ......
     *      *s Video Camera Line 2 combinedDevice: off
     *      *s Video Camera Line 2 numberOfCombinedDevices: 0
     *      *s Video Camera Line 2 ptzComId: -1
     *
     * @throws Exception during ssh communication
     */
    private Map<String, String> retrieveCameraLineParameters() throws Exception {
        String cameraLine = execute(ZSTATUS_CAMERA_LINE, "*s Video Camera Line");
        return parseZoomRoomsProperties(cameraLine, "*s Video Camera Line");
    }

    /**
     * Parse properties parameters based on the provided start of the line,
     * so the expected format of data provided here is:
     * Video Camera Line 1 Name: Camera Name
     * .....
     * Video Camera Line 2 Name: Camera Name
     *
     * @param response           string from Zoom Rooms CLI
     * @param propertyIdentifier supposed start of the string
     * @return map of a format <Video Camera Line 1 Name: Camera Name, Video Camera Line 2 Name: Camera Name....>
     */
    private Map<String, String> parseZoomRoomsProperties(String response, String propertyIdentifier) {
        Map<String, String> parameters = new HashMap<>();
        Arrays.asList(response.split("\n")).forEach(s -> {
            if (s.startsWith(propertyIdentifier)) {
                String[] values = s.split(":");
                parameters.put(values[0].replace("*s ", ""), values[1].replace("\r", "").trim());
            }
        });
        return parameters;
    }

    /**
     * Join zoom meeting that is currently in progress or start a scheduled one.
     *
     * In order to connect, regular expression is used on the dialString - (\d+)\.?(\d+)?(@[a-z]+?\.[a-z]{2,5}):
     * (\d+)\.?(\d+)? - matches numeric sequence (supposed meetingNumber) or 2 sequences separated by a dot character
     * (meetingNumber.meetingPassword). Password is optional, as well as the dot separator.
     *
     * (@[a-z]+?\.[a-z]{2,5}) - checks for the domain to make sure dialString parameter matches the general pattern.
     * We can't rely on any specific zoom SIP domain(s), so the pattern is expecting any simple name with a 2-5 chars
     * top level domain.
     *
     * If the dialString is any regular (non-zoom) SIP/H323 address - the call will not succeed, pattern will not
     * match and IllegalArgumentException exception will be thrown.
     * If the dialString is a non-zoom SIP address, which still matches the expected pattern - the start/join command
     * will be attempted, but it will not succeed, unless exactly matches an existing Zoom meetingNumber/password.
     *
     * @param dialString - contains dialString of a format %meetingNumber%.%meetingPassword%@%zoomSIPDomain%
     *                     password is optional.
     * @return meetingId if operation is successful
     *
     * @throws IllegalArgumentException if dialString does not match the expected Zoom meeting SIP address pattern
     */
    private String joinStartMeeting(String dialString) throws Exception {
        boolean hasPassword;
        String meetingNumber;
        String meetingPassword;

        Pattern dialStringPattern = Pattern.compile("(\\d+)\\.?(\\d+)?(@[a-z]+?\\.[a-z]{2,5})");
        Matcher matcher = dialStringPattern.matcher(dialString);
        if (!matcher.find() && !matcher.matches()) {
            // This might be the section to call out SIP/h323 devices it "invite" functionality fits our needs
            // However, for the simplified approach, this option is skipped for now
            throw new IllegalArgumentException("Dial string does not match expected pattern ddddddddd.dddddd@sssssss.sssss");
        } else {
            hasPassword = !StringUtils.isNullOrEmpty(matcher.group(2));
            meetingNumber = matcher.group(1);
            meetingPassword = hasPassword ? matcher.group(2) : "";
        }

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Attempt to join or start a meeting with meetingNumber: %s and password: %s", meetingNumber, meetingPassword));
        }

        if (executeAndVerify(String.format(ZCOMMAND_DIAL_JOIN, meetingNumber) + (hasPassword ? (" password:" + meetingPassword) : "") + "\r", "*r DialJoinResult (status=OK)")) {
            isHost = false;
            if (logger.isDebugEnabled()) {
                logger.debug("Joining the meeting: " + meetingNumber);
            }
            return meetingNumber;
        } else {
            if (executeAndVerify(String.format(ZCOMMAND_DIAL_START, meetingNumber) + (hasPassword ? (" password:" + meetingPassword) : "") + "\r", "*r DialJoinResult (status=OK)")) {
                isHost = true;
                if (logger.isDebugEnabled()) {
                    logger.debug("Starting the meeting: " + meetingNumber);
                }
                return meetingNumber;
            }
            throw new RuntimeException("Failed to connect to a meeting with dial string: " + dialString);
        }
    }

    /**
     * Retrieve call status based on a response string, underscores are omitted
     *
     * @return one of the following: "in meeting" | "connecting meeting" | "not in meeting"
     */
    private String getCallStatus() throws Exception {
        String response = execute(ZCOMMAND_CALL_STATUS, "*s Call Status:");
        String[] callStatus = response.toLowerCase().split("call status:");

        return callStatus[1].substring(0, callStatus[1].indexOf("** end")).trim().replaceAll("_", " ");
    }

    /**
     * Retrieve current active meetingId from the call info
     *
     * @return {@link String} value of meetingId that ZoomRooms is currently connected to
     * @throws Exception while ssh communication
     */
    private String getMeetingId() throws Exception {
        String response = execute(ZCOMMAND_CALL_INFO, "*r InfoResult (status=OK)");
        return parseZoomRoomsProperties(response, "*r InfoResult Info meeting_id").get("*r InfoResult Info meeting_id");
    }

    /**
     * Retrieve ZR microphone mute status
     *
     * @return true if muted, false if unmuted
     * @throws Exception during ssh communication
     */
    private boolean getMuteStatus() throws Exception {
        String muteStatus = execute(ZCOMMAND_MUTE_STATUS, "*c zConfiguration Call Microphone Mute").toLowerCase();
        return muteStatus.substring(muteStatus.indexOf("mute:") + 5, muteStatus.lastIndexOf("** end")).trim().equals(ON);
    }

    /**
     * Retrieve ZR camera mute status
     *
     * @return true if muted, false if unmuted
     * @throws Exception during ssh communication
     */
    private boolean retrieveCameraMuteStatus() throws Exception {
        String muteStatus = execute(ZCOMMAND_CAMERA_MUTE_STATUS, "*c zConfiguration Call Camera Mute").toLowerCase();
        return muteStatus.substring(muteStatus.indexOf("mute:") + 5, muteStatus.lastIndexOf("** end")).trim().equals(ON);
    }

    /**
     * Change ZR microphone mute status.
     * While not in the meeting - it's not possible to change or retrieve microphone mute status.
     *
     * @param status on/off indicating the mute status
     * @throws Exception during ssh communication
     */
    private void switchMuteStatus(boolean status) throws Exception {
        String meetingStatus = getCallStatus();
        if (!meetingStatus.equals(IN_MEETING)) {
            throw new IllegalStateException("Not in a meeting. Not able to change mute status.");
        }
        String command = status ? ON : OFF;
        executeAndVerify(String.format(ZCOMMAND_MUTE, command), "*c zConfiguration Call Microphone Mute");
    }

    /**
     * Change ZR camera mute status
     * While not in the meeting - it's not possible to change or retrieve camera mute status.
     *
     * @param status true or false indicating the mute status
     * @throws Exception during ssh communication
     */
    private void switchCameraMuteStatus(boolean status) throws Exception {
        String meetingStatus = getCallStatus();
        if (!meetingStatus.equals(IN_MEETING)) {
            throw new IllegalStateException("Not in a meeting. Not able to change camera mute status.");
        }
        String command = status ? ON : OFF;
        executeAndVerify(String.format(ZCOMMAND_CAMERA_MUTE, command), "*c zConfiguration Call Camera Mute");
    }

    /**
     * Disconnect from the active call or from an idle call (ZR is trying to connect)
     *
     * @return true if disconnected successfully, false if an error has occurred
     */
    private boolean callDisconnect() throws Exception {
        if (isHost) {
            return executeAndVerify(ZCOMMAND_CALL_DISCONNECT, "*r CallDisconnectResult");
        } else {
            return executeAndVerify(ZCOMMAND_CALL_LEAVE, "*r CallDisconnectResult");
        }
    }

    /**
     * Execute command using execute() method and simply check whether the command completed successfully
     *
     * @param command      to perform
     * @param verifyString string that should be present in a response payload
     * @return boolean value indicating whether the operation is successful
     */
    private boolean executeAndVerify(String command, String verifyString) throws Exception {
        String response = execute(command, verifyString);
        return !StringUtils.isNullOrEmpty(response);
    }

    /**
     * Execute the command and check the response to contain "verifyString" in a few attempts.
     * It's not added into the general "CommandSuccess/Failure" list because the basic communicator
     * checks for endsWith() but there's no guarantee that the response will end with a certain string,
     * also sometimes there are multiple comands perfomed automatically on entry with multiple "** end" breaks.
     * This part helps to have another layer of response verification.
     *
     * @param command      to perform
     * @param verifyString string that should be present in a response payload
     * @return empty string or a command response, if successful
     */
    private String execute(String command, String verifyString) throws Exception {
        String response;
        controlOperationsLock.lock();
        try {
            refreshSshConnection();
            response = send(command);
            int retryAttempts = 0;
            while (!response.contains(verifyString)) {
                retryAttempts++;
                response = send(command);
                if (retryAttempts >= 10) {
                    throw new IllegalStateException(String.format("Failed to verify response for command %s. Expected output: %s", command, verifyString));
                }
            }
        } finally {
            controlOperationsLock.unlock();
        }
        return response;
    }

    /**
     * Check if the channel is connected and if not - reconnect
     */
    private void refreshSshConnection() throws Exception {
        if (!isChannelConnected()) {
            connect();
        }
    }

    @Override
    public String dial(DialDevice dialDevice) throws Exception {
        if (StringUtils.isNullOrEmpty(dialDevice.getDialString())) {
            throw new InvalidArgumentException("Dial string is empty.");
        }
        String meetingStatus = getCallStatus();
        if (meetingStatus.equals(IN_MEETING)) {
            String meetingNumber = getMeetingId();
            if(logger.isDebugEnabled()) {
                logger.debug("Not able to connect. Meeting " + meetingNumber + " is in progress.");
            }
            return meetingNumber;
        } else if (meetingStatus.equals(CONNECTING_MEETING)) {
            // Need to be able to recover if terminal gets stuck in a "connecting" state.
            // This may happen if some specific non-existing meeting numbers are used (this may happen by accident)
            // in which case ZR app will stuck without giving an ability to disconnect (unless it's done manually)
            // So it is assumed that if we're getting here - it's either multiple calls are being addressed one right
            // after another, which is less likely, or that the terminal got stuck because of this issue.
            callDisconnect();
        }
        return joinStartMeeting(dialDevice.getDialString());
    }

    @Override
    public void hangup(String s) throws Exception {
        String meetingStatus = getCallStatus();
        if (!meetingStatus.equals(IN_MEETING) && !meetingStatus.equals(CONNECTING_MEETING)) {
            return;
        }
        callDisconnect();
    }

    @Override
    public CallStatus retrieveCallStatus(String s) throws Exception {
        CallStatus callStatus = new CallStatus();
        callStatus.setCallStatusState(getCallStatus().equals(IN_MEETING) ? CallStatus.CallStatusState.Connected : CallStatus.CallStatusState.Disconnected);
        callStatus.setCallId(getMeetingId());
        return callStatus;
    }

    /**
     * {@inheritDoc}
     *
     * Attempting to retrieve ZoomRooms' mute status while ZoomRooms is not connected to any meeting will
     * end up with an error. This is the case for both retrieving mute state and updating it since the same
     * command is used for both - {@link #ZCOMMAND_MUTE_STATUS} and {@link #ZCOMMAND_MUTE}
     */
    @Override
    public MuteStatus retrieveMuteStatus() throws Exception {
        String meetingStatus = getCallStatus();
        if (!meetingStatus.equals(IN_MEETING)) {
            return MuteStatus.Unmuted;
        }
        return getMuteStatus() ? MuteStatus.Muted : MuteStatus.Unmuted;
    }

    @Override
    public void sendMessage(PopupMessage popupMessage) throws Exception {
        throw new UnsupportedOperationException("Send message functionality is not supported");
    }

    @Override
    public void mute() throws Exception {
        switchMuteStatus(true);
    }

    @Override
    public void unmute() throws Exception {
        switchMuteStatus(false);
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        switch (property) {
            case "Video Camera#Move Up":
                executeAndVerify("zCommand Call CameraControl Id:0 State:Start Action:Up\r", "*r CameraControl (status=OK)");
                break;
            case "Video Camera#Move Down":
                executeAndVerify("zCommand Call CameraControl Id:0 State:Start Action:Down\r", "*r CameraControl (status=OK)");
                break;
            case "Video Camera#Move Left":
                executeAndVerify("zCommand Call CameraControl Id:0 State:Start Action:Left\r", "*r CameraControl (status=OK)");
                break;
            case "Video Camera#Move Right":
                executeAndVerify("zCommand Call CameraControl Id:0 State:Start Action:Right\r", "*r CameraControl (status=OK)");
                break;
            case "Call Control#Video Camera Mute":
                switchCameraMuteStatus(value.equals("1"));
                break;
            case "Call Control#Microphone Mute":
                switchMuteStatus(value.equals("1"));
                break;
            default:
                logger.debug("Not implemented yet");
                break;
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        if (CollectionUtils.isEmpty(list)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }

        for (ControllableProperty controllableProperty : list) {
            controlProperty(controllableProperty);
        }
    }
}
