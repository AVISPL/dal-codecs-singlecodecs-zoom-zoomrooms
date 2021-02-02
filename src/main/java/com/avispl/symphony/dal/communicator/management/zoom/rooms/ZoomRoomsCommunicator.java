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
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.SshCommunicator;
import com.avispl.symphony.dal.util.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;
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

    private enum CameraMovementDirection {Up, Down, Left, Right}

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

    /**
     *  A number of attempts to perform for getting the conference (call) status while performing
     * {@link #dial(DialDevice)} operation
     */
    private static final int MAX_STATUS_POLL_ATTEMPT = 5;
    /**
     * Entries that help to verify whether certain commands were successful or not.
     * Key represents command and value - the expected response substring.
     */
    private Map<String, String> commandsVerifiers = new HashMap<>();

    private boolean isHost = false;

    /**
     * ZoomRoomsCommunicator instantiation
     * Providing a list of success/error for login and other commands as well as addint entries to {@link #commandsVerifiers}
     * map, which is used for {@link #doneReading(String, String)} checks
     */
    public ZoomRoomsCommunicator() {
        setCommandSuccessList(Collections.singletonList("** end\r\n\r\nOK\r\n"));
        setLoginSuccessList(Arrays.asList("\r\n** end\r\n\n", "*r Login successful\r\nOK\r\n\n"));
        setLoginErrorList(Collections.singletonList("Permission denied, please try again.\n"));
        setCommandErrorList(Arrays.asList("*e Connection rejected\r\n\n", "ERROR\r\n\n"));

        /* Trim is done once per adapter instantiation, but it's easier to see which command does entry refer to */
        commandsVerifiers.put(ZSTATUS_AUDIO_INPUT_LINE.trim(), "*s Audio Input Line");
        commandsVerifiers.put(ZSTATUS_AUDIO_OUTPUT_LINE.trim(), "*s Audio Output Line");
        commandsVerifiers.put(ZSTATUS_SYSTEM_UNIT.trim(), "*s SystemUnit");
        commandsVerifiers.put(ZSTATUS_CAMERA_LINE.trim(), "*s Video Camera Line");
        commandsVerifiers.put(ZCOMMAND_CALL_STATUS.trim(), "*s Call Status:");
        commandsVerifiers.put(ZCOMMAND_CALL_INFO.trim(), "*r InfoResult");
        commandsVerifiers.put(ZCOMMAND_MUTE_STATUS.trim(), "*c zConfiguration Call Microphone Mute");
        commandsVerifiers.put(ZCOMMAND_CAMERA_MUTE_STATUS.trim(), "*c zConfiguration Call Camera Mute");
        commandsVerifiers.put(ZCOMMAND_CALL_DISCONNECT.trim(), "*r CallDisconnectResult");
        commandsVerifiers.put(ZCOMMAND_CALL_LEAVE.trim(), "*r CallDisconnectResult");

        /* Some commands variables have a parameter added, so to preserve uniqueness - generic part of such commands
        * is used as an entry. */
        commandsVerifiers.put("zcommand dial join meetingNumber", "*r DialJoinResult");
        commandsVerifiers.put("zcommand dial start meetingNumber", "*r DialStartResult");
        commandsVerifiers.put("zcommand call cameracontrol id", "*r CameraControl");
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
            controls.add(createSwitch("Call Control#Microphone Mute", getMuteStatus() ? 1 : 0));
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
        String audioInputLine = send(ZSTATUS_AUDIO_INPUT_LINE);
        String audioOutputLine = send(ZSTATUS_AUDIO_OUTPUT_LINE);
        String systemUnit = send(ZSTATUS_SYSTEM_UNIT);

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
        String cameraLine = send(ZSTATUS_CAMERA_LINE);
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
     * In ZoomRooms, CLI communication with generic SIP/H323 endpoints is done using 2 additional commands, which
     * imply starting ZR PMI first:
     *      zCommand Call InviteSipRoom Address: %s cancel: on/off
     *      zCommand Call InviteH323Room Address: %s cancel: on/off
     * Future use of these commands will require to have a way to reliably distinguish between Zoom SIP addresses and
     * generic SIP/H323.
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

        /* Attempting to start a meeting and if the command does not succeed - joining the meeting instead,
        * since there's no way currently to pass start/join type of dial action. Since the target destination
        * may be someone's PMI - it won't start before the host joins the meeting, so in case of successful
        * start/join commands we populate target meetingNumber (since the request itself has succeeded).
        * Otherwise, in case if ZoomRooms is waiting for the host to join, which can take some time, calling
        * getMeetingId() will result with a timeout error (the operation does not provide any feedback while
        * ZR is in the described state, so it fails with an error and is only supposed to be used while ZR is in the call)
        *
        * Possible errors are handled by the communicator, so if the 'join' fallback does not work -
        * CommandFailureException is thrown and the operation is considered unsuccessful.
        * */
        try {
            send(String.format(ZCOMMAND_DIAL_START, meetingNumber) + (hasPassword ? (" password:" + meetingPassword) : "") + "\r");
            isHost = true;
            if (logger.isDebugEnabled()) {
                logger.debug("Starting the meeting: " + meetingNumber);
            }
            return meetingNumber;
        } catch (CommandFailureException cfe) {
            if(logger.isDebugEnabled()) {
                logger.debug(String.format("Unable to start meeting %s, switching to join", meetingNumber));
            }
            send(String.format(ZCOMMAND_DIAL_JOIN, meetingNumber) + (hasPassword ? (" password:" + meetingPassword) : "") + "\r");
            isHost = false;
            if (logger.isDebugEnabled()) {
                logger.debug("Joining the meeting: " + meetingNumber);
            }
            return meetingNumber;
        }
    }

    /**
     * Retrieve call status based on a response string, underscores are omitted
     *
     * @return one of the following: "in meeting" | "connecting meeting" | "not in meeting"
     */
    private String getCallStatus() throws Exception {
        String response = send(ZCOMMAND_CALL_STATUS);
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
        String response = send(ZCOMMAND_CALL_INFO);
        return parseZoomRoomsProperties(response, "*r InfoResult Info meeting_id").get("*r InfoResult Info meeting_id");
    }

    /**
     * Retrieve ZR microphone mute status
     *
     * @return true if muted, false if unmuted
     * @throws Exception during ssh communication
     */
    private boolean getMuteStatus() throws Exception {
        String muteStatus = send(ZCOMMAND_MUTE_STATUS).toLowerCase();
        return muteStatus.substring(muteStatus.indexOf("mute:") + 5, muteStatus.lastIndexOf("** end")).trim().equals(ON);
    }

    /**
     * Retrieve ZR camera mute status
     *
     * @return true if muted, false if unmuted
     * @throws Exception during ssh communication
     */
    private boolean retrieveCameraMuteStatus() throws Exception {
        String muteStatus = send(ZCOMMAND_CAMERA_MUTE_STATUS).toLowerCase();
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
        send(String.format(ZCOMMAND_MUTE, command));
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
        send(String.format(ZCOMMAND_CAMERA_MUTE, command));
    }

    /**
     * Disconnect from the active call or from an idle call (ZR is trying to connect)
     */
    private void callDisconnect() throws Exception {
        if (isHost) {
            send(ZCOMMAND_CALL_DISCONNECT);
        } else {
            send(ZCOMMAND_CALL_LEAVE);
        }
    }

    /**
     * {@inheritDoc}
     *
     * This methods overrides the done reading because, similarly to the way some Cisco CLIs work,
     * some commands end with "** end" and some have it in the middle and with "OK" while the others have "OK"
     * in the middle. Also, eventually ZR CLI returns additional data, such as Phonebook entries, for instance,
     * so we need to react to a specific response strings for each zcommand.
     * So, the custom solution will work for commands that start with zcommand/zstatus/zconfiguration, for the rest -
     * such as login, a default implementation works fine, since we can ignore the rest of the payload there
     * if no exceptions were thrown during the operation
     */
    @Override
    protected boolean doneReading(String command, String response) throws CommandFailureException {
        /* Need to lower case it in case there are new commands that are not set strictly to lower case when sent */
        String lowerCaseCommand = command.toLowerCase();
        if (lowerCaseCommand.startsWith("zcommand") || lowerCaseCommand.startsWith("zstatus") || lowerCaseCommand.startsWith("zconfiguration")) {
            for (String string : getCommandErrorList()) {
                if (response.endsWith(string)) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("Custom Done reading, found Error string: " + string);
                    }
                    throw new CommandFailureException(host, command, response);
                }
            }

            /* Some commands have a parameter set after ':' character, since this is not relevant for
            * the response validation - anything after the colon is stripped, including the colon character */
            String verifyString = commandsVerifiers.get(command.split(":")[0].trim());
            String trimmedResponse = response.trim();
            /* Need to make sure specific substring is there as well as the 'normal' output breakers */
            if (response.contains(verifyString) && (trimmedResponse.endsWith("OK") || trimmedResponse.endsWith("** end"))) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Custom Done reading");
                }
                return true;
            }
            return false;
        }
        return super.doneReading(command, response);
    }

    /**
     * Check if the channel is connected and if not - reconnect
     */
    private void refreshSshConnection() throws Exception {
        if (!isChannelConnected()) {
            connect();
        }
    }

    /**
     * {@inheritDoc}
     *
     * Need to be able to recover if terminal gets stuck in a permanent "connecting" state.
     * This may happen if some specific non-existing meeting numbers are used (this may happen by accident) or
     * a password is not provided for a meeting requiring one, in which case ZR app will stuck without giving
     * an ability to disconnect (unless it's done manually), so it is assumed that if we're getting here -
     * it's either multiple calls are being addressed one right after another, which is less likely,
     * or that the terminal got stuck because of this issue.
     *
     * Current workaround for a "frozen" call attempts is to check callStatus 5 times with a 1s delay in
     * between the checks and if the connection is not resolved by then - the stale call is disconnected
     * and the device is requested to join/start the requested meeting. Unfortunately, it's not possible
     * to retrieve the meetingId for the meeting, that is in "connecting" state (ZR is not associated
     * with a meeting)
     */
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
            for (int i = 0; i < MAX_STATUS_POLL_ATTEMPT; i++) {
                meetingStatus = getCallStatus();
                if (!meetingStatus.equals(CONNECTING_MEETING) && !meetingStatus.equals(IN_MEETING)) {
                    return joinStartMeeting(dialDevice.getDialString());
                } else if (meetingStatus.equals(IN_MEETING)) {
                    return getMeetingId();
                }
                Thread.sleep(1000);
            }
            if(logger.isDebugEnabled()) {
                logger.debug("ZoomRooms device is in 'connecting' state, unable to resolve, issuing new connection.");
            }
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

    /**
     * {@inheritDoc}
     *
     * It's not possible to retrieve meetingId (callInfo) if the device is not in the call, fetching the information
     * will lead to the "CallInfoResult(status=Error): Result reason: "Not in meeting"" error,
     * so ZR meetingId is only fetched when the device is in the call, otherwise -
     * input parameter callId value is provided.
     */
    @Override
    public CallStatus retrieveCallStatus(String callId) throws Exception {
        CallStatus callStatus = new CallStatus();
        String callStatusResponse = getCallStatus();
        if (callStatusResponse.equals(IN_MEETING)) {
            callStatus.setCallStatusState(CallStatus.CallStatusState.Connected);
            callStatus.setCallId(getMeetingId());
        } else {
            callStatus.setCallStatusState(CallStatus.CallStatusState.Disconnected);
            callStatus.setCallId(callId);
        }
        return callStatus;
    }

    /**
     * {@inheritDoc}
     *
     * Attempting to retrieve ZoomRooms' mute status while ZoomRooms is not connected to any meeting will
     * end up with an error. This is the case for both retrieving mute state and updating it since the same
     * command is used for both - {@link #ZCOMMAND_MUTE_STATUS} and {@link #ZCOMMAND_MUTE}, so if the device
     * is not in the call - null MuteStatus is populated
     */
    @Override
    public MuteStatus retrieveMuteStatus() throws Exception {
        String meetingStatus = getCallStatus();
        if (!meetingStatus.equals(IN_MEETING)) {
            return null;
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

    /**
     * Move camera to a direction passed with {@link CameraMovementDirection}
     *
     * @param direction - movement direction
     * @throws Exception if any errors occur
     */
    private void moveCamera(CameraMovementDirection direction) throws Exception {
        send(String.format("zcommand call cameracontrol id:0 state:start action:%s\r", direction));
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        switch (property) {
            case "Video Camera#Move Up":
                moveCamera(CameraMovementDirection.Up);
                break;
            case "Video Camera#Move Down":
                moveCamera(CameraMovementDirection.Down);
                break;
            case "Video Camera#Move Left":
                moveCamera(CameraMovementDirection.Left);
                break;
            case "Video Camera#Move Right":
                moveCamera(CameraMovementDirection.Right);
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
