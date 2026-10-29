package org.javacord.core.util.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import org.apache.logging.log4j.Logger;
import org.javacord.api.Javacord;
import org.javacord.api.audio.SpeakingFlag;
import org.javacord.api.util.crypto.AudioEncryptor;
import org.javacord.core.DiscordApiImpl;
import org.javacord.core.audio.AudioConnectionImpl;
import org.javacord.core.util.crypto.AudioEncryptionMode;
import org.javacord.core.util.dave.DaveSessionManager;
import org.javacord.core.util.dave.LibDaveLoader;
import org.javacord.core.util.logging.LoggerUtil;
import org.javacord.core.util.logging.WebSocketLogger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DataFormatException;

public class AudioWebSocketAdapter extends WebSocketAdapter {

    private static final Logger logger = LoggerUtil.getLogger(AudioWebSocketAdapter.class);

    private static final Set<Integer> DAVE_BINARY_OPCODES = Set.of(
            VoiceGatewayOpcode.DAVE_MLS_EXTERNAL_SENDER.getCode(),
            VoiceGatewayOpcode.DAVE_MLS_PROPOSALS.getCode(),
            VoiceGatewayOpcode.DAVE_MLS_COMMIT_TRANSITION.getCode(),
            VoiceGatewayOpcode.DAVE_MLS_WELCOME.getCode()
    );

    private final AudioConnectionImpl connection;
    private final DiscordApiImpl api;
    private final AtomicReference<WebSocket> websocket = new AtomicReference<>();
    private final Heart heart;

    private AudioUdpSocket socket;
    private int ssrc;
    private volatile DaveSessionManager daveManager;

    /**
     * Guards creating and closing {@link #daveManager}: the websocket thread creates it while
     * {@link #disconnect()} and {@link #cleanupDaveResources()} may run on other threads.
     */
    private final Object daveLock = new Object();

    /**
     * Set once this adapter is disconnected for good; no DAVE session is created afterwards.
     */
    private boolean daveClosed;

    /**
     * Users the voice gateway reported in this session (opcodes 11 and 13). Tracked from the start,
     * because they can arrive before the session description creates the DAVE session.
     */
    private final Set<String> connectedUserIds = ConcurrentHashMap.newKeySet();

    /**
     * A boolean to indicate if the websocket should try to reconnect.
     *
     * <p>Used to prevent automatic reconnects after a wanted websocket close.
     */
    private volatile boolean reconnect;

    /**
     * Whether a {@link VoiceGatewayOpcode#RESUME} should be sent or a normal connect.
     */
    private volatile boolean resuming;

    // A reconnect attempt counter
    private final AtomicInteger reconnectAttempt = new AtomicInteger();

    /**
     * Created a new audio websocket adapter.
     *
     * @param connection The connection for the adapter.
     */
    public AudioWebSocketAdapter(AudioConnectionImpl connection) {
        this.connection = connection;
        reconnect = true;
        api = (DiscordApiImpl) connection.getChannel().getApi();
        heart = new Heart(
                api,
                heartbeatFrame -> websocket.get().sendFrame(heartbeatFrame),
                (code, reason) -> websocket.get().sendClose(code, reason),
                true);
        connect();
    }

    @Override
    public void onTextMessage(WebSocket websocket, String text) throws Exception {
        ObjectMapper mapper = api.getObjectMapper();
        JsonNode packet = mapper.readTree(text);

        heart.handlePacket(packet);

        int op = packet.get("op").asInt();
        Optional<VoiceGatewayOpcode> opcode = VoiceGatewayOpcode.fromCode(op);
        if (!opcode.isPresent()) {
            logger.debug("Received unknown audio websocket packet ({}, op: {}, content: {})",
                    connection, op, packet);
            return;
        }

        JsonNode data;
        switch (opcode.get()) {
            case HELLO:
                logger.debug("Received {} packet for {}", opcode.get().name(), connection);
                if (!resuming) {
                    sendIdentify(websocket);
                }
                data = packet.get("d");
                int heartbeatInterval = data.get("heartbeat_interval").asInt();
                heart.startBeating(heartbeatInterval);
                break;
            case READY:
                logger.debug("Received {} packet for {}", opcode.get().name(), connection);
                data = packet.get("d");

                String ip = data.get("ip").asText();
                int port = data.get("port").asInt();
                JsonNode modes = data.get("modes");
                ssrc = data.get("ssrc").asInt();

                logger.debug("Voice encryption modes available {}", modes);
                Optional<AudioEncryptionMode> encryptionMode = AudioEncryptionMode.getBestMode(modes);
                if (!encryptionMode.isPresent()) {
                    logger.error(
                            "None of the received encryption modes {} are able to be used by Javacord!"
                                    + "Please contact the developer!",
                            modes
                    );
                    connection.close();
                    return;
                } else {
                    logger.debug("Voice encryption mode {} selected.", encryptionMode.get());
                }

                Optional<AudioEncryptor> audioEncryptor = encryptionMode.get().getAudioEncryptor();
                if (!audioEncryptor.isPresent()) {
                    logger.error(
                            "There is no associated AudioEncryptor for mode {}. "
                                    + "Therefore, a connection cannot be established."
                                    + "Please contact the developer!",
                            encryptionMode.get()
                    );
                    connection.close();
                    return;
                } else {
                    logger.debug("Using AudioEncryptor {}", audioEncryptor.get().getClass());
                }

                socket = new AudioUdpSocket(connection, new InetSocketAddress(ip, port), ssrc, audioEncryptor.get());

                sendSelectProtocol(websocket, encryptionMode.get());
                break;
            case SESSION_DESCRIPTION:
                sendSpeaking(websocket);

                data = packet.get("d");
                byte[] secretKey = api.getObjectMapper().convertValue(data.get("secret_key"), byte[].class);
                socket.setSecretKey(secretKey);

                int daveProtocolVersion = data.has("dave_protocol_version")
                        ? data.get("dave_protocol_version").asInt(0) : 0;

                // With libdave loaded, a DAVE session exists even for version 0: it passes frames through
                // and can be upgraded later (prepare epoch 1)
                if (LibDaveLoader.isAvailable()) {
                    DaveSessionManager manager = initializeDaveSession(daveProtocolVersion);
                    socket.setDaveEncryptor(manager == null ? null : manager.getEncryptor());
                    logger.debug("DAVE protocol version {} negotiated for {}", daveProtocolVersion, connection);
                } else {
                    closeDaveSession();
                    if (daveProtocolVersion > 0) {
                        logger.warn("Voice gateway requested DAVE protocol version {} but libdave is not available. "
                                + "Voice may not work correctly for {}", daveProtocolVersion, connection);
                    }
                }

                // The connection is usable now. Like the reference clients, the DAVE handshake continues in
                // parallel: frames pass through until a transition executes, then they are E2EE.
                socket.startSending();
                connection.getReadyFuture().complete(connection);
                break;
            case CLIENT_CONNECT:
                // {"user_ids": [...]}
                data = packet.get("d");
                if (data.has("user_ids")) {
                    for (JsonNode userIdNode : data.get("user_ids")) {
                        String connectUserId = userIdNode.asText();
                        connectedUserIds.add(connectUserId);
                        DaveSessionManager connectManager = daveManager;
                        if (connectManager != null) {
                            connectManager.addRecognizedUser(connectUserId);
                        }
                    }
                    logger.debug("Clients connected: {} for {}", data.get("user_ids"), connection);
                }
                break;
            case CLIENT_DISCONNECT:
                // {"user_id": "..."}
                data = packet.get("d");
                if (data.has("user_id")) {
                    String disconnectUserId = data.get("user_id").asText();
                    connectedUserIds.remove(disconnectUserId);
                    DaveSessionManager disconnectManager = daveManager;
                    if (disconnectManager != null) {
                        disconnectManager.removeRecognizedUser(disconnectUserId);
                    }
                    logger.debug("Client disconnected: {} for {}", disconnectUserId, connection);
                }
                break;
            case DAVE_PREPARE_TRANSITION:
                data = packet.get("d");
                DaveSessionManager prepareManager = daveManager;
                if (prepareManager != null) {
                    int transProtocolVersion = data.get("protocol_version").asInt(0);
                    int transId = data.get("transition_id").asInt(0);
                    prepareManager.handlePrepareTransition(transProtocolVersion, transId);
                }
                break;
            case DAVE_EXECUTE_TRANSITION:
                data = packet.get("d");
                DaveSessionManager executeManager = daveManager;
                if (executeManager != null) {
                    executeManager.handleExecuteTransition(data.get("transition_id").asInt(0));
                }
                break;
            case DAVE_PREPARE_EPOCH:
                data = packet.get("d");
                DaveSessionManager epochManager = daveManager;
                if (epochManager != null) {
                    long epoch = data.get("epoch").asLong(0);
                    int epochProtocolVersion = data.get("protocol_version").asInt(0);
                    epochManager.handlePrepareEpoch(epoch, epochProtocolVersion);
                }
                break;
            case HEARTBEAT_ACK:
                break;
            case RESUMED:
                resuming = false;
                reconnectAttempt.set(0);
                logger.info("Successfully resumed audio websocket connection for {}", connection);
                break;
            default:
                break;
        }
    }

    @Override
    public void onBinaryMessage(WebSocket websocket, byte[] binary) throws Exception {
        if (binary.length >= 3 && DAVE_BINARY_OPCODES.contains(binary[2] & 0xFF)) {
            // Server binary frames start with a big-endian uint16 sequence number that must be acked
            heart.handleBinarySequence(((binary[0] & 0xFF) << 8) | (binary[1] & 0xFF));
            handleDaveBinaryMessage(binary);
            return;
        }

        String message;
        try {
            message = BinaryMessageDecompressor.decompress(binary);
        } catch (DataFormatException e) {
            logger.warn("An error occurred while decompressing data", e);
            return;
        }
        logger.trace("onTextMessage: text='{}'", message);
        onTextMessage(websocket, message);
    }

    @Override
    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
        if (resuming) {
            sendResume(websocket);
            socket.startSending();
        }
    }

    @Override
    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame,
                               WebSocketFrame clientCloseFrame, boolean closedByServer) {

        Optional<WebSocketFrame> closeFrameOptional =
                Optional.ofNullable(closedByServer ? serverCloseFrame : clientCloseFrame);

        WebSocketCloseCode closeCode = closeFrameOptional
                .flatMap(closeFrame -> WebSocketCloseCode.fromCodeForVoice(closeFrame.getCloseCode()))
                .orElse(WebSocketCloseCode.UNKNOWN);

        String closeReason = closeFrameOptional
                .map(WebSocketFrame::getCloseReason)
                .orElse("unknown");

        String closeCodeString = closeCode + " ("
                + (closeCode == WebSocketCloseCode.UNKNOWN ? "Unknown" : closeCode.getCode()) + ")";


        logger.info("Websocket closed with reason '{}' and code {} by {} for {}!",
                closeReason, closeCodeString, closedByServer ? "server" : "client", connection);

        heart.squash();
        if (socket != null) {
            socket.stopSending();
        }

        if (resuming) {
            logger.info("Could not resume, reconnecting in {} seconds", api.getReconnectDelay(reconnectAttempt.get()));
            resuming = false;
            reconnectAttempt.set(0);
            api.getThreadPool().getScheduler()
                    .schedule(this::connect, api.getReconnectDelay(reconnectAttempt.get()), TimeUnit.SECONDS);
            return;
        }

        switch (closeCode) {
            case AUTHENTICATION_FAILED:
            case SERVER_NOT_FOUND:
                connection.close();
                break;
            case SESSION_NO_LONGER_VALID:
            case DISCONNECTED:
                if (!connection.getReadyFuture().isDone()) {
                    connection.getReadyFuture().completeExceptionally(
                            new IllegalStateException(
                                    "Audio websocket closed with reason '"
                                            + closeReason
                                            + "' and code "
                                            + closeCodeString
                                            + " by "
                                            + (closedByServer ? "server" : "client")
                                            + " before "
                                            + VoiceGatewayOpcode.SESSION_DESCRIPTION.name()
                                            + " packet was received"
                            )
                    );
                }
                disconnect();
                // TODO There are multiple reasons for a disconnect close code and we do not want to reconnect
                //  for all of them (e.g., when the channel was deleted).
                connection.reconnect();
                break;
            case UNKNOWN_ERROR:
            case UNKNOWN_OPCODE:
            case UNKNOWN_PROTOCOL:
            case UNKNOWN_ENCRYPTION_MODE:
            case VOICE_SERVER_CRASHED:
                resuming = true;
                logger.info("Trying to resume audio websocket in {} seconds!",
                        api.getReconnectDelay(reconnectAttempt.get()));
                api.getThreadPool().getScheduler()
                        .schedule(this::connect, api.getReconnectDelay(reconnectAttempt.get()), TimeUnit.SECONDS);
                break;
            case NORMAL:
                if (!closedByServer && connection.getDisconnectFuture() != null) {
                    connection.getDisconnectFuture().complete(null);
                } else {
                    reconnect();
                }
                break;
            default:
                reconnect();
                break;
        }
    }

    /**
     * Routes a DAVE binary frame to the appropriate session manager handler.
     *
     * <p>Server-to-client binary frames have the format:
     * {@code [uint16 sequence][uint8 opcode][payload...]}.
     * The 3-byte header is stripped before passing the payload to handlers.
     *
     * @param frame The complete binary frame including the 3-byte header.
     */
    private void handleDaveBinaryMessage(byte[] frame) {
        DaveSessionManager manager = daveManager;
        if (manager == null) {
            logger.debug("Received DAVE binary opcode {} but no session manager is active", frame[2] & 0xFF);
            return;
        }

        int opcode = frame[2] & 0xFF;
        byte[] payload = Arrays.copyOfRange(frame, 3, frame.length);

        Optional<VoiceGatewayOpcode> gatewayOpcode = VoiceGatewayOpcode.fromCode(opcode);
        if (!gatewayOpcode.isPresent()) {
            logger.debug("Received unknown DAVE binary opcode {}", opcode);
            return;
        }

        logger.debug("Received DAVE binary opcode {} ({}) for {} [daveState={}]",
                opcode, gatewayOpcode.get().name(), connection, manager.getState());

        switch (gatewayOpcode.get()) {
            case DAVE_MLS_EXTERNAL_SENDER:
                manager.handleExternalSender(payload);
                break;
            case DAVE_MLS_PROPOSALS:
                manager.handleProposals(payload);
                break;
            case DAVE_MLS_COMMIT_TRANSITION:
                manager.handleCommitTransition(payload);
                break;
            case DAVE_MLS_WELCOME:
                manager.handleWelcome(payload);
                break;
            default:
                logger.debug("Unhandled DAVE binary opcode {} for {}", opcode, connection);
                break;
        }
    }

    /**
     * Replaces the DAVE session manager with a new one for this session description.
     *
     * @param protocolVersion The DAVE protocol version from the session description (may be 0).
     * @return The new manager, or {@code null} if this adapter is closed or libdave failed.
     */
    private DaveSessionManager initializeDaveSession(int protocolVersion) {
        synchronized (daveLock) {
            closeDaveSession();
            if (daveClosed) {
                return null;
            }
            // Always send through the current websocket: a resume replaces it but keeps this manager
            DaveSessionManager.VoiceGatewaySender sender = new DaveSessionManager.VoiceGatewaySender() {
                @Override
                public void sendBinaryFrame(byte[] frame) {
                    websocket.get().sendBinary(frame);
                }

                @Override
                public void sendTextFrame(String text) {
                    websocket.get().sendFrame(WebSocketFrame.createTextFrame(text));
                }
            };

            // The MLS group is the voice channel (as in the reference clients); libdave rejects commits
            // and welcomes for any other group ID
            DaveSessionManager manager = new DaveSessionManager(
                    () -> connection.getChannel().getId(),
                    connection.getServer().getApi().getYourself().getIdAsString(),
                    sender);
            connectedUserIds.forEach(manager::addRecognizedUser);
            // in case a member's voice state was cached before opcode 11 arrived
            for (long userId : connection.getChannel().getConnectedUserIds()) {
                manager.addRecognizedUser(String.valueOf(userId));
            }
            try {
                manager.initialize(protocolVersion, ssrc);
            } catch (RuntimeException e) {
                logger.error("Could not set up DAVE for {}; continuing without E2EE", connection, e);
                manager.close();
                return null;
            }
            daveManager = manager;
            return manager;
        }
    }

    /**
     * Detaches the DAVE encryptor from the send loop, then frees the native DAVE resources.
     */
    private void closeDaveSession() {
        synchronized (daveLock) {
            if (socket != null) {
                socket.setDaveEncryptor(null);
            }
            DaveSessionManager manager = daveManager;
            daveManager = null;
            if (manager != null) {
                manager.close();
            }
        }
    }

    /**
     * Gets the DAVE session manager, if active.
     *
     * @return The DAVE session manager, or {@code null} if DAVE is not active.
     */
    public DaveSessionManager getDaveManager() {
        return daveManager;
    }

    /**
     * Connects the websocket.
     */
    private void connect() {
        String endpoint = "wss://"
                + connection.getEndpoint().replace(":80", "")
                + "?v="
                + Javacord.DISCORD_VOICE_GATEWAY_VERSION;
        logger.debug("Trying to connect to websocket {}", endpoint);
        WebSocketFactory factory = new WebSocketFactory();
        try {
            factory.setSSLContext(SSLContext.getDefault());
        } catch (NoSuchAlgorithmException e) {
            logger.warn("An error occurred while setting ssl context", e);
        }
        try {
            WebSocket websocket = factory
                    .createSocket(endpoint);
            this.websocket.set(websocket);
            websocket.addHeader("Accept-Encoding", "gzip");
            websocket.addListener(this);
            websocket.addListener(new WebSocketLogger());
            websocket.connect();
        } catch (Throwable t) {
            logger.warn("An error occurred while connecting to audio websocket for {} ({})", connection, t.getCause());
            if (reconnect) {
                reconnectAttempt.incrementAndGet();
                logger.info("Trying to reconnect/resume audio websocket in {} seconds!",
                        api.getReconnectDelay(reconnectAttempt.get()));
                // Reconnect after a (short?) delay depending on the amount of reconnect attempts
                api.getThreadPool().getScheduler()
                        .schedule(this::connect, api.getReconnectDelay(reconnectAttempt.get()), TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Starts a reconnect attempt.
     */
    private void reconnect() {
        reconnectAttempt.incrementAndGet();
        logger.info("Trying to reconnect audio websocket in {} seconds!",
                api.getReconnectDelay(reconnectAttempt.get()));
        // Reconnect after a (short?) delay depending on the amount of reconnect attempts
        api.getThreadPool().getScheduler()
                .schedule(this::connect, api.getReconnectDelay(reconnectAttempt.get()), TimeUnit.SECONDS);
    }

    /**
     * Releases DAVE native resources without disconnecting the websocket.
     *
     * <p>Called during reconnect to ensure native handles are freed when
     * the adapter is being replaced.
     */
    public void cleanupDaveResources() {
        closeDaveSession();
    }

    /**
     * Disconnects from the websocket and releases DAVE resources.
     */
    public void disconnect() {
        reconnect = false;
        if (socket != null) {
            socket.stopSending();
        }
        synchronized (daveLock) {
            daveClosed = true;
            closeDaveSession();
        }
        websocket.get().sendClose(WebSocketCloseReason.DISCONNECT.getNumericCloseCode());
        api.getThreadPool().getDaemonScheduler().schedule(heart::squash, 1, TimeUnit.MINUTES);
    }

    /**
     * Sends the resume packet.
     *
     * @param websocket The websocket the resume packet should be sent to.
     */
    private void sendResume(WebSocket websocket) {
        ObjectNode resumePacket = JsonNodeFactory.instance.objectNode()
                .put("op", VoiceGatewayOpcode.RESUME.getCode());
        ObjectNode data = resumePacket.putObject("d");
        data.put("server_id", connection.getServer().getIdAsString())
                .put("session_id", connection.getSessionId())
                .put("token", connection.getToken())
                .put("seq_ack", heart.getLastSeq());
        logger.debug("Sending resume packet for {}", connection);
        WebSocketFrame resumeFrame = WebSocketFrame.createTextFrame(resumePacket.toString());
        websocket.sendFrame(resumeFrame);
    }

    /**
     * Sends the identify packet.
     *
     * <p>When libdave is available, includes {@code max_dave_protocol_version}
     * to signal DAVE E2EE support to the voice gateway.
     *
     * @param websocket The websocket the identify packet should be sent to.
     */
    private void sendIdentify(WebSocket websocket) {
        // A new session starts a new sequence
        heart.resetSequence();
        connectedUserIds.clear();
        ObjectNode identifyPacket = JsonNodeFactory.instance.objectNode()
                .put("op", VoiceGatewayOpcode.IDENTIFY.getCode());
        ObjectNode data = identifyPacket.putObject("d");
        data.put("server_id", connection.getServer().getIdAsString())
                .put("user_id", connection.getServer().getApi().getYourself().getIdAsString())
                .put("session_id", connection.getSessionId())
                .put("token", connection.getToken());

        if (LibDaveLoader.isAvailable()) {
            int maxDaveVersion = LibDaveLoader.getInstance().daveMaxSupportedProtocolVersion();
            data.put("max_dave_protocol_version", maxDaveVersion);
            logger.debug("Sending voice identify packet with max_dave_protocol_version={} for {}",
                    maxDaveVersion, connection);
        } else {
            logger.debug("Sending voice identify packet (DAVE unavailable) for {}", connection);
        }

        WebSocketFrame identifyFrame = WebSocketFrame.createTextFrame(identifyPacket.toString());
        websocket.sendFrame(identifyFrame);
    }

    /**
     * Sends a "select protocol" packet.
     *
     * @param websocket The websocket the packet should be sent to.
     * @throws IOException  If an I/O error occurs.
     */
    private void sendSelectProtocol(WebSocket websocket, AudioEncryptionMode mode) throws IOException {
        InetSocketAddress address = socket.discoverIp();
        ObjectNode selectProtocolPacket = JsonNodeFactory.instance.objectNode();
        selectProtocolPacket
                .put("op", VoiceGatewayOpcode.SELECT_PROTOCOL.getCode())
                .putObject("d")
                .put("protocol", "udp")
                .putObject("data")
                .put("address", address.getHostString())
                .put("port", address.getPort())
                .put("mode", mode.toString());
        logger.debug("Sending select protocol packet for {}", connection);
        WebSocketFrame selectProtocolFrame = WebSocketFrame.createTextFrame(selectProtocolPacket.toString());
        websocket.sendFrame(selectProtocolFrame);
    }

    /**
     * Sends the speaking packet.
     *
     * @param websocket The websocket the packet should be sent to.
     */
    private void sendSpeaking(WebSocket websocket) {
        ObjectNode speakingPacket = JsonNodeFactory.instance.objectNode();
        int speakingFlags = 0;
        for (SpeakingFlag flag : connection.getSpeakingFlags()) {
            speakingFlags |= flag.asInt();
        }
        speakingPacket
                .put("op", VoiceGatewayOpcode.SPEAKING.getCode())
                .putObject("d")
                .put("speaking", speakingFlags)
                .put("delay", 0)
                .put("ssrc", ssrc);
        logger.debug("Sending speaking packet for {} (packet: {})", connection, speakingPacket);
        WebSocketFrame speakingFrame = WebSocketFrame.createTextFrame(speakingPacket.toString());
        websocket.sendFrame(speakingFrame);
    }

    /**
     * Sends the speaking packet.
     */
    public void sendSpeaking() {
        sendSpeaking(websocket.get());
    }
}
