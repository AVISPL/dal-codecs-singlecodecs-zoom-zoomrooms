/*
 * Copyright (c) 2015-2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.management.zoom.rooms;

import com.avispl.symphony.api.dal.control.call.CallController;
import com.avispl.symphony.api.dal.dto.control.call.CallStatus;
import com.avispl.symphony.api.dal.dto.control.call.DialDevice;
import com.avispl.symphony.api.dal.dto.control.call.MuteStatus;
import com.avispl.symphony.api.dal.dto.control.call.PopupMessage;
import com.avispl.symphony.api.dal.dto.monitor.*;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.SshCommunicator;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An SshCommunicator-based adapter to provide communication with ZoomRooms software
 * Monitoring features:
 *      - Call status
 *      - Audio input mute status
 *      - General Zoom Rooms information (Room Name, Version, Meeting Number, Active Meeting Number, Account Email)
 *      - Audio Input/Output settings
 *      - Video Camera settings
 * Controlling features:
 *      - Connect to a meeting
 *      - Disconnect from the meeting
 *      - Mute Zoom Rooms audio input
 *      - Unmute Zoom Rooms audio input
 */
public class ZoomRoomsCommunicator extends SshCommunicator implements CallController, Monitorable {

//    private static final String ZCOMMAND_INVITE_SIP = "zCommand Call InviteSipRoom Address: %s cancel: %s";
//    private static final String ZCOMMAND_INVITE_H323 = "zCommand Call InviteH323Room Address: %s cancel: %s";
    // zstatus sharing
    // zstatus numberofscreens
    //
    private static final String ZCOMMAND_DIAL_START = "zcommand dial start meetingNumber:%s";
    private static final String ZCOMMAND_DIAL_JOIN = "zcommand dial join meetingNumber:%s";
    private static final String ZCOMMAND_CALL_LEAVE = "zcommand call leave\r";
    private static final String ZCOMMAND_CALL_DISCONNECT = "zcommand call disconnect\r";
    private static final String ZCOMMAND_CALL_STATUS = "zstatus call status\r";
    private static final String ZCOMMAND_MUTE_STATUS = "zconfiguration call microphone mute\r";
    private static final String ZCOMMAND_MUTE = "zconfiguration call microphone mute: %s\r";
    private static final String ZSTATUS_AUDIO_INPUT_LINE = "zstatus audio input line\r";
    private static final String ZSTATUS_AUDIO_OUTPUT_LINE = "zstatus audio output line\r";
    private static final String ZSTATUS_CAMERA_LINE = "zstatus video camera line\r";
    private static final String ZSTATUS_SYSTEM_UNIT = "zstatus systemunit\r";

    private final ReentrantLock controlOperationsLock = new ReentrantLock();

    private String meetingNumber;
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
        refreshSshConnection();

        String callStatus = getCallStatus();
        endpointStatistics.setInCall(callStatus.equals("in meeting"));

        if (endpointStatistics.isInCall()) {
            boolean microphoneMuted = getMuteStatus();
            AudioChannelStats audioChannelStats = new AudioChannelStats();
            audioChannelStats.setMuteTx(microphoneMuted);
            endpointStatistics.setAudioChannelStats(audioChannelStats);
            CallStats callStats = new CallStats();
            callStats.setCallId(meetingNumber);
            endpointStatistics.setCallStats(callStats);
        }

        Map<String, String> statistics = new HashMap<>();
        getExtendedStatus(statistics);
        if(endpointStatistics.isInCall()){
            statistics.put("Active Meeting Number", meetingNumber);
        } else {
            statistics.remove("Active Meeting Number");
        }
        extendedStatistics.setStatistics(statistics);
        return Arrays.asList(endpointStatistics, extendedStatistics);
    }

    /**
     * Get extended status data for Zoom Rooms instance.
     * In order to verify the response as valid - corresponding strings are provided.
     * @param statistics map to save data to
     * @throws Exception if an error occurred during communication
     */
    private void getExtendedStatus(Map<String, String> statistics) throws Exception {
        String audioInputLine = execute(ZSTATUS_AUDIO_INPUT_LINE, "*s Audio Input Line");
        String audioOutputLine = execute(ZSTATUS_AUDIO_OUTPUT_LINE, "*s Audio Output Line");
        String cameraLine = execute(ZSTATUS_CAMERA_LINE, "*s Video Camera Line");
        String systemUnit = execute(ZSTATUS_SYSTEM_UNIT, "*s SystemUnit");

        Map<String, String> lineParameters = parseZoomRoomsProperties(audioInputLine, "*s Audio Input Line");
        lineParameters.putAll(parseZoomRoomsProperties(audioOutputLine, "*s Audio Output Line"));
        lineParameters.putAll(parseZoomRoomsProperties(cameraLine, "*s Video Camera Line"));

        Map<String, String> systemUnitParameters = parseZoomRoomsProperties(systemUnit, "*s SystemUnit");

        lineParameters.forEach((key, value) -> {
            if(key.endsWith("Name") || key.endsWith(" Selected")){
                if(key.startsWith("Audio")){
                    statistics.put("Audio Settings#" + key, value);
                } else if (key.startsWith("Video Camera")){
                    statistics.put("Video Camera Settings#" + key, value);
                }
            }
        });

        systemUnitParameters.forEach((key, value) -> {
            String keyValue = key.replace("SystemUnit ", "").replace("room_info", "").trim();
            switch (keyValue){
                case "room_version":
                    statistics.put("Zoom Rooms Version", value);
                    break;
                case "meeting_number":
                    statistics.put("Meeting Number", value);
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
     * Parse properties parameters based on the provided start of the line,
     * so the expected format of data provided here is:
     *      Video Camera Line 1 Name: Camera Name
     *      .....
     *      Video Camera Line 2 Name: Camera Name
     *
     * @param response string from Zoom Rooms CLI
     * @param propertyIdentifier supposed start of the string
     * @return map of a format <Video Camera Line 1 Name: Camera Name, Video Camera Line 2 Name: Camera Name....>
     */
    private Map<String, String> parseZoomRoomsProperties(String response, String propertyIdentifier){
        Map<String, String> parameters = new HashMap<>();
        Arrays.asList(response.split("\n")).forEach(s -> {
            if(s.startsWith(propertyIdentifier)){
                String[] values = s.split(":");
                parameters.put(values[0].replace("*s ", ""), values[1].replace("\r", ""));
            }
        });
        return parameters;
    }

    /**
     * Join meeting that is currently in progress or start a scheduled one.
     * @param value - contains dialString of a format %meetingNumber%.%meetingPassword%@zoomcrc.com
     *                password is optional.
     * @return meetingId if operation is successful
     */
    private String joinStartMeeting(String value) throws Exception {
        boolean hasPassword;
        String meetingNumber;
        String meetingPassword;

        Pattern dialStringPattern = Pattern.compile("(\\d+)\\.?(\\d+)?(@zoomcrc\\.com)");
        Matcher matcher = dialStringPattern.matcher(value);
        if(!matcher.find() && !matcher.matches()){
            // This might be the section to call out SIP/h323 devices it "invite" functionality fits our needs
            if(logger.isErrorEnabled()) {
                logger.error("Dial string does not match expected pattern meetingNumber.meetingPassword@zoomcrc.com. Skipping.");
            }
            throw new IllegalArgumentException("Dial string does not match expected pattern meetingNumber.meetingPassword@zoomcrc.com");
        } else {
            hasPassword = !StringUtils.isEmpty(matcher.group(2));
            meetingNumber = matcher.group(1);
            meetingPassword = hasPassword ? matcher.group(2) : "";
        }

        if(logger.isDebugEnabled()){
            logger.debug(String.format("Attempt to join or start a meeting with meetingNumber: %s and password: %s", meetingNumber, meetingPassword));
        }

        if(executeAndVerify(String.format(ZCOMMAND_DIAL_JOIN, meetingNumber) + (hasPassword ? (" password:" + meetingPassword) : "") + "\r", "*r DialJoinResult (status=OK)")){
            isHost = false;
            if(logger.isDebugEnabled()){
                logger.debug("Joining the meeting: " + meetingNumber);
            }
            this.meetingNumber = meetingNumber;
            return meetingNumber;
        } else {
            if(executeAndVerify(String.format(ZCOMMAND_DIAL_START, meetingNumber) + (hasPassword ? (" password:" + meetingPassword) : "") + "\r", "*r DialJoinResult (status=OK)")){
                isHost = true;
                if(logger.isDebugEnabled()){
                    logger.debug("Starting the meeting: " + meetingNumber);
                }
                this.meetingNumber = meetingNumber;
                return meetingNumber;
            }
        }
        throw new IllegalStateException("Failed to connect to a meeting with dial string: " + value);
    }

    /**
     * Retrieve call status based on a response string, underscores are omitted
     * @return one of the following: "in meeting" | "connecting meeting" | "not in meeting"
     *
     */
    private String getCallStatus() throws Exception {
        String response = execute(ZCOMMAND_CALL_STATUS, "*s Call Status:");
        String[] callStatus = response.toLowerCase().split("call status:");

        return callStatus[1].substring(0, callStatus[1].indexOf("** end")).trim().replaceAll("_", " ");
    }

    /**
     * Retrieve ZR microphone mute status
     * @return true if muted, false if unmuted
     */
    private boolean getMuteStatus() throws Exception {
        String muteStatus = execute(ZCOMMAND_MUTE_STATUS, "*c zConfiguration Call Microphone Mute").toLowerCase();
        return muteStatus.substring(muteStatus.indexOf("mute:") + 5, muteStatus.lastIndexOf("** end")).trim().equals("on");
    }

    /**
     * Change ZR microphone mute status
     * @param status true or false indicating the mute status
     * @return true if operation if successful, false if an error has occured
     */
    private boolean switchMuteStatus (boolean status) throws Exception {
        String command = status ? "on" : "off";
        return executeAndVerify(String.format(ZCOMMAND_MUTE, command), "*c zConfiguration Call Microphone Mute");
    }

    /**
     * Disconnect from the active call or from an idle call (ZR is trying to connect)
     * @return true if disconnected successfully, false if an error has occured
     */
    private boolean callDisconnect() throws Exception {
        meetingNumber = "";
        if(isHost){
            return executeAndVerify(ZCOMMAND_CALL_DISCONNECT, "*r CallDisconnectResult");
        } else {
            return executeAndVerify(ZCOMMAND_CALL_LEAVE, "*r CallDisconnectResult");
        }
    }

    /**
     * Execute command using execute() method and simply check whether the command completed successfully
     * @param command to perform
     * @param verifyString string that should be present in a response payload
     * @return boolean value indicating whether the operation is successful
     */
    private boolean executeAndVerify(String command, String verifyString) throws Exception {
        String response = execute(command, verifyString);
        return !StringUtils.isEmpty(response);
    }

    /**
     * Execute the command and check the response to contain "verifyString" in a few attempts.
     * It's not added into the general "CommandSuccess/Failure" list because the basic communicator
     * checks for endsWith() but there's no guarantee that the response will end with a certain string,
     * also sometimes there are multiple comands perfomed automatically on entry with multiple "** end" breaks.
     * This part helps to have another layer of response verification.
     *
     * @param command to perform
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
            while (!response.contains(verifyString)){
                retryAttempts++;
                response = send(command);
                if(retryAttempts >= 10) {
                    return "";
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
        String meetingStatus = getCallStatus();
        if(meetingStatus.equals("in meeting")){
            logger.warn("Not able to connect. Meeting " + meetingNumber + " is in progress.");
            return null;
        } else if (meetingStatus.equals("connecting meeting")) {
            callDisconnect();
        }
        return joinStartMeeting(dialDevice.getDialString());
    }

    @Override
    public void hangup(String s) throws Exception {
        String meetingStatus = getCallStatus();
        if(!meetingStatus.equals("in meeting") && !meetingStatus.equals("connecting meeting")){
            logger.warn("Not able to disconnect. Not connected to a meeting.");
            return;
        }
        callDisconnect();
    }

    @Override
    public CallStatus retrieveCallStatus(String s) throws Exception {
        CallStatus callStatus = new CallStatus();
        callStatus.setCallStatusState(getCallStatus().equals("in meeting") ? CallStatus.CallStatusState.Connected : CallStatus.CallStatusState.Disconnected);
        callStatus.setCallId(meetingNumber);
        return callStatus;

    }

    @Override
    public MuteStatus retrieveMuteStatus() throws Exception {
        String meetingStatus = getCallStatus();
        if(!meetingStatus.equals("in meeting")){
            return MuteStatus.Unmuted;
        }
        return getMuteStatus() ? MuteStatus.Muted : MuteStatus.Unmuted;
    }

    @Override
    public void sendMessage(PopupMessage popupMessage) throws Exception {
        if(logger.isDebugEnabled()) {
            logger.debug("SendMessage operation is not supported. Message received: " + popupMessage.getMessage());
        }
    }

    @Override
    public void mute() throws Exception {
        switchMuteStatus(true);
    }

    @Override
    public void unmute() throws Exception {
        switchMuteStatus(false);
    }
}
