package org.javacord.core.util.dave;

import com.sun.jna.Pointer;
import org.apache.logging.log4j.Logger;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the full DAVE E2EE protocol lifecycle for a single voice connection.
 *
 * <p>Manages the MLS session state machine, frame encryptor/decryptor, recognized
 * user tracking, and epoch transitions. All DAVE voice gateway opcodes are routed
 * through this manager.
 *
 * <p>The state machine progresses through:
 * <ul>
 *   <li>{@link State#INACTIVE} - No DAVE (protocol version 0 or libdave unavailable)</li>
 *   <li>{@link State#PENDING} - Waiting for external sender and key package exchange</li>
 *   <li>{@link State#AWAITING_GROUP} - Key package sent, waiting for welcome or own commit</li>
 *   <li>{@link State#ESTABLISHED} - MLS group active, sender key ratchets available</li>
 *   <li>{@link State#TRANSITIONING} - Epoch change in progress</li>
 * </ul>
 */
public class DaveSessionManager implements AutoCloseable {

    private static final Logger logger = LoggerUtil.getLogger(DaveSessionManager.class);

    private static final int INIT_TRANSITION_ID = 0;

    /**
     * The possible states of the DAVE session lifecycle.
     */
    public enum State {
        INACTIVE,
        PENDING,
        AWAITING_GROUP,
        ESTABLISHED,
        TRANSITIONING
    }

    private final long guildId;
    private final String selfUserId;
    private final VoiceGatewaySender gatewaySender;

    private final LibDave lib;
    private DaveSession session;
    private DaveFrameEncryptor encryptor;
    private DaveFrameDecryptor decryptor;

    private volatile State state = State.INACTIVE;
    private volatile int protocolVersion;
    private volatile int pendingTransitionId = -1;
    private int ssrc;
    private byte[] lastExternalSender;

    private final Set<String> recognizedUserIds = ConcurrentHashMap.newKeySet();

    /**
     * Callback interface for sending voice gateway messages.
     *
     * <p>DAVE uses both binary frames (for MLS protocol data) and text frames
     * (for JSON-encoded transition control messages).
     */
    public interface VoiceGatewaySender {

        /**
         * Sends a binary websocket frame on the voice gateway.
         *
         * @param frame The complete binary frame to send.
         */
        void sendBinaryFrame(byte[] frame);

        /**
         * Sends a text websocket frame on the voice gateway.
         *
         * @param text The JSON text to send.
         */
        void sendTextFrame(String text);
    }

    /**
     * Creates a new DAVE session manager.
     *
     * @param guildId       The guild ID for this voice session.
     * @param selfUserId    The bot's own user ID as a string.
     * @param gatewaySender Callback for sending voice gateway messages.
     */
    public DaveSessionManager(long guildId, String selfUserId, VoiceGatewaySender gatewaySender) {
        this(guildId, selfUserId, gatewaySender, LibDaveLoader.getInstance());
    }

    DaveSessionManager(long guildId, String selfUserId, VoiceGatewaySender gatewaySender, LibDave lib) {
        this.guildId = guildId;
        this.selfUserId = selfUserId;
        this.gatewaySender = gatewaySender;
        this.lib = lib;
    }

    /**
     * Initializes the DAVE session after receiving a non-zero {@code dave_protocol_version}
     * in the SESSION_DESCRIPTION (opcode 4) response.
     *
     * @param protocolVersion The DAVE protocol version selected by the voice gateway.
     * @param ssrc            The SSRC assigned to this client.
     */
    public void initialize(int protocolVersion, int ssrc) {
        if (lib == null) {
            logger.error("Cannot initialize DAVE session: libdave not available. "
                    + "Install libdave on your system to use voice channels with E2EE.");
            state = State.INACTIVE;
            return;
        }

        this.protocolVersion = protocolVersion;
        this.ssrc = ssrc;

        cleanup();
        session = new DaveSession(lib);
        session.init(protocolVersion, guildId, selfUserId);
        encryptor = new DaveFrameEncryptor(lib);
        encryptor.setPassthroughMode(true);
        encryptor.assignSsrcToOpus(ssrc);
        decryptor = new DaveFrameDecryptor(lib);
        decryptor.transitionToPassthroughMode(true);

        state = State.PENDING;
        logger.debug("DAVE session initialized with protocol version {} for guild {}", protocolVersion, guildId);
    }

    /**
     * Handles the MLS external sender package (opcode 25, binary).
     *
     * <p>Sets the external sender on the MLS session and generates a key package
     * to send to the voice gateway.
     *
     * @param externalSenderData The raw binary payload after the opcode/sequence header.
     */
    public void handleExternalSender(byte[] externalSenderData) {
        if (state == State.INACTIVE || session == null) {
            return;
        }

        State oldState = state;
        lastExternalSender = externalSenderData.clone();
        session.setExternalSender(externalSenderData);
        sendKeyPackage();
        state = State.AWAITING_GROUP;
        logger.debug("Received external sender ({} bytes), sent key package for guild {} "
                + "[state: {} -> AWAITING_GROUP, recognizedUsers: {}]",
                externalSenderData.length, guildId, oldState, recognizedUserIds.size());
    }

    /**
     * Handles MLS proposals from the voice gateway (opcode 27, binary).
     *
     * <p>Processes the proposals and, if this client is a committing member,
     * generates and sends a commit/welcome message.
     *
     * @param proposalsData The raw binary proposals payload.
     */
    public void handleProposals(byte[] proposalsData) {
        if (session == null || state != State.ESTABLISHED) {
            logger.debug("Ignoring proposals for guild {} [session={}, state={}]",
                    guildId, session != null ? "present" : "null", state);
            return;
        }

        logger.debug("Processing proposals for guild {} [recognizedUsers({}): {}]",
                guildId, recognizedUserIds.size(), recognizedUserIds);
        Optional<byte[]> commitWelcome = session.processProposals(proposalsData, recognizedUserIds);
        commitWelcome.ifPresent(bytes -> {
            sendBinaryMessage(28, bytes);
            logger.debug("Processed proposals and sent commit/welcome for guild {}", guildId);
        });
    }

    /**
     * Handles an MLS commit transition from the voice gateway (opcode 29, binary).
     *
     * <p>Processes the commit and prepares new key ratchets for the next epoch.
     * The payload format after stripping the 3-byte server header is:
     * {@code [uint16 transition_id][MLS commit data...]}.
     *
     * @param payload The binary payload containing transition ID and MLS commit data.
     */
    public void handleCommitTransition(byte[] payload) {
        if (session == null || payload.length < 2) {
            logger.debug("Ignoring commit transition for guild {} [session={}, payloadLen={}]",
                    guildId, session != null ? "present" : "null", payload.length);
            return;
        }
        if (state != State.ESTABLISHED && state != State.TRANSITIONING) {
            logger.debug("Ignoring commit transition for guild {} [state={}, expected ESTABLISHED or TRANSITIONING]",
                    guildId, state);
            return;
        }

        int transitionId = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        byte[] commitData = new byte[payload.length - 2];
        System.arraycopy(payload, 2, commitData, 0, commitData.length);

        logger.debug("Processing commit transition {} for guild {} [state={}, commitDataLen={}]",
                transitionId, guildId, state, commitData.length);

        try (DaveCommitResult result = session.processCommit(commitData)) {
            if (result.isFailed()) {
                logger.warn("Failed to process MLS commit for guild {}, sending invalid commit "
                        + "[transitionId={}, state={}, recognizedUsers({}): {}]",
                        guildId, transitionId, state, recognizedUserIds.size(), recognizedUserIds);
                sendInvalidCommitWelcome(transitionId);
                resetAndResendKeyPackage();
                return;
            }

            if (result.isIgnored()) {
                logger.warn("MLS commit ignored (group mismatch) for guild {}, resetting session "
                        + "[transitionId={}, state={}]", guildId, transitionId, state);
                sendInvalidCommitWelcome(transitionId);
                resetAndResendKeyPackage();
                return;
            }

            prepareSenderKeyRatchets();
            pendingTransitionId = transitionId;
            state = State.TRANSITIONING;
            sendTransitionReady(transitionId);
            logger.debug("Processed commit, ready for transition {} in guild {}", transitionId, guildId);
        }
    }

    /**
     * Handles an MLS welcome message (opcode 30, binary).
     *
     * <p>Processes the welcome to join an existing MLS group and prepares
     * key ratchets for decryption.
     * The payload format after stripping the 3-byte server header is:
     * {@code [uint16 transition_id][MLS welcome data...]}.
     *
     * @param payload The binary payload containing transition ID and MLS welcome data.
     */
    public void handleWelcome(byte[] payload) {
        if (session == null || payload.length < 2) {
            logger.warn("Ignoring welcome for guild {} [session={}, payloadLen={}]",
                    guildId, session != null ? "present" : "null", payload.length);
            return;
        }

        int transitionId = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        byte[] welcome = new byte[payload.length - 2];
        System.arraycopy(payload, 2, welcome, 0, welcome.length);

        logger.info("Processing welcome {} for guild {} [state={}, welcomeDataLen={}, "
                + "recognizedUsers({}): {}]",
                transitionId, guildId, state, welcome.length,
                recognizedUserIds.size(), recognizedUserIds);

        try (DaveWelcomeResult result = session.processWelcome(welcome, recognizedUserIds)) {
            if (result.isFailed()) {
                logger.warn("Failed to process MLS welcome for guild {}, sending invalid commit "
                        + "[transitionId={}, state={}, recognizedUsers({}): {}]",
                        guildId, transitionId, state, recognizedUserIds.size(), recognizedUserIds);
                sendInvalidCommitWelcome(transitionId);
                resetAndResendKeyPackage();
                return;
            }

            prepareSenderKeyRatchets();
            pendingTransitionId = transitionId;
            state = State.TRANSITIONING;
            sendTransitionReady(transitionId);
            logger.info("Processed welcome, sent TRANSITION_READY for transition {} in guild {} "
                    + "[state={}]", transitionId, guildId, state);
        }
    }

    /**
     * Handles the prepare transition opcode (opcode 21, JSON).
     *
     * <p>Announces an upcoming protocol version transition. When transitioning
     * to version 0, enables passthrough mode on the encryptor/decryptor.
     *
     * @param transitionProtocolVersion The target protocol version.
     * @param transitionId              The transition ID.
     */
    public void handlePrepareTransition(int transitionProtocolVersion, int transitionId) {
        if (transitionProtocolVersion == 0) {
            if (encryptor != null) {
                encryptor.setPassthroughMode(true);
            }
            if (decryptor != null) {
                decryptor.transitionToPassthroughMode(true);
            }
        }

        if (transitionId == INIT_TRANSITION_ID) {
            executeTransitionImmediately(transitionId);
        } else {
            pendingTransitionId = transitionId;
            sendTransitionReady(transitionId);
        }

        logger.debug("Prepared transition {} to protocol version {} for guild {}",
                transitionId, transitionProtocolVersion, guildId);
    }

    /**
     * Handles the execute transition opcode (opcode 22, JSON).
     *
     * <p>Executes the previously announced transition, applying new sender key
     * ratchets to the encryptor.
     *
     * @param transitionId The transition ID to execute.
     */
    public void handleExecuteTransition(int transitionId) {
        executeTransitionImmediately(transitionId);
        logger.info("Executed transition {} for guild {} [state={}]", transitionId, guildId, state);
    }

    /**
     * Handles the prepare epoch opcode (opcode 24, JSON).
     *
     * <p>When epoch is 1, resets the MLS session and sends a new key package
     * to create or re-create the MLS group.
     *
     * @param epoch           The epoch ID.
     * @param protocolVersion The protocol version for the new epoch.
     */
    public void handlePrepareEpoch(long epoch, int protocolVersion) {
        State oldState = state;
        this.protocolVersion = protocolVersion;

        if (epoch == 1) {
            if (session != null) {
                session.reset();
                session.setProtocolVersion(protocolVersion);
                if (lastExternalSender != null) {
                    session.setExternalSender(lastExternalSender);
                }
                logger.debug("Session reset for epoch 1 in guild {} [externalSender={}]",
                        guildId, lastExternalSender != null ? "restored" : "none");
            }
            sendKeyPackage();
            state = State.AWAITING_GROUP;
            logger.debug("Reset MLS group for epoch 1 in guild {} [state: {} -> AWAITING_GROUP]",
                    guildId, oldState);
        } else {
            logger.debug("Prepare epoch {} for guild {} [state={}, protocolVersion={}]",
                    epoch, guildId, state, protocolVersion);
        }
    }

    /**
     * Registers a user as a recognized voice session participant.
     *
     * @param userId The user ID string.
     */
    public void addRecognizedUser(String userId) {
        boolean added = recognizedUserIds.add(userId);
        if (added) {
            logger.debug("Added recognized user {} for guild {} [total: {}]",
                    userId, guildId, recognizedUserIds.size());
        }
    }

    /**
     * Removes a user from the recognized voice session participants.
     *
     * @param userId The user ID string.
     */
    public void removeRecognizedUser(String userId) {
        boolean removed = recognizedUserIds.remove(userId);
        if (removed) {
            logger.debug("Removed recognized user {} for guild {} [total: {}]",
                    userId, guildId, recognizedUserIds.size());
        }
    }

    /**
     * Gets the DAVE frame encryptor for use in the audio send pipeline.
     *
     * @return The encryptor, or {@code null} if DAVE is not active.
     */
    public DaveFrameEncryptor getEncryptor() {
        return encryptor;
    }

    /**
     * Gets the DAVE frame decryptor for use in a future audio receive pipeline.
     *
     * @return The decryptor, or {@code null} if DAVE is not active.
     */
    public DaveFrameDecryptor getDecryptor() {
        return decryptor;
    }

    /**
     * Gets the current state of the DAVE session lifecycle.
     *
     * @return The current state.
     */
    public State getState() {
        return state;
    }

    /**
     * Returns whether DAVE E2EE is actively encrypting frames.
     *
     * @return {@code true} if the session is established and encryption is active.
     */
    public boolean isEncryptionActive() {
        return state == State.ESTABLISHED
                && encryptor != null
                && encryptor.hasKeyRatchet()
                && !encryptor.isPassthroughMode();
    }

    /**
     * Returns whether DAVE E2EE is supported (libdave loaded and protocol version non-zero).
     *
     * @return {@code true} if DAVE is available for this session.
     */
    public boolean isDaveActive() {
        return state != State.INACTIVE && protocolVersion > 0;
    }

    private void sendKeyPackage() {
        if (session == null) {
            logger.debug("Cannot send key package for guild {}: session is null", guildId);
            return;
        }
        Optional<byte[]> keyPackage = session.getMarshalledKeyPackage();
        if (keyPackage.isPresent()) {
            sendBinaryMessage(26, keyPackage.get());
            logger.debug("Sent key package ({} bytes) for guild {}", keyPackage.get().length, guildId);
        } else {
            logger.warn("Failed to generate key package for guild {} [state={}]", guildId, state);
        }
    }

    private void prepareSenderKeyRatchets() {
        if (session == null) {
            return;
        }

        Pointer selfRatchet = session.getKeyRatchet(selfUserId);
        if (selfRatchet != null && encryptor != null) {
            encryptor.setKeyRatchet(selfRatchet);
        }
    }

    private void executeTransitionImmediately(int transitionId) {
        State oldState = state;
        if (encryptor != null && protocolVersion > 0) {
            encryptor.setPassthroughMode(false);
            logger.debug("Encryptor passthrough disabled for guild {} (DAVE encryption active)", guildId);
        }

        state = State.ESTABLISHED;
        pendingTransitionId = -1;
        logger.debug("Executed transition {} for guild {} [state: {} -> ESTABLISHED]",
                transitionId, guildId, oldState);
    }

    private void sendTransitionReady(int transitionId) {
        sendJsonMessage(23, transitionId);
    }

    private void sendInvalidCommitWelcome(int transitionId) {
        sendJsonMessage(31, transitionId);
    }

    private void resetAndResendKeyPackage() {
        State oldState = state;
        logger.debug("Resetting DAVE session for guild {} [oldState={}, protocolVersion={}, "
                + "recognizedUsers({}): {}]",
                guildId, oldState, protocolVersion,
                recognizedUserIds.size(), recognizedUserIds);
        if (encryptor != null) {
            encryptor.setPassthroughMode(true);
            logger.debug("Encryptor set to passthrough mode for guild {}", guildId);
        }
        if (session != null) {
            session.reset();
            session.init(protocolVersion, guildId, selfUserId);
            if (lastExternalSender != null) {
                session.setExternalSender(lastExternalSender);
            }
            logger.debug("Session reset and re-initialized for guild {} [externalSender={}]",
                    guildId, lastExternalSender != null ? "restored" : "none");
        }
        sendKeyPackage();
        state = State.AWAITING_GROUP;
        logger.debug("Recovery: sent key package, state {} -> AWAITING_GROUP for guild {}", oldState, guildId);
    }

    private void sendBinaryMessage(int opcode, byte[] data) {
        byte[] frame = new byte[data.length + 1];
        frame[0] = (byte) opcode;
        System.arraycopy(data, 0, frame, 1, data.length);
        gatewaySender.sendBinaryFrame(frame);
    }

    private void sendJsonMessage(int opcode, int transitionId) {
        String json = String.format("{\"op\":%d,\"d\":{\"transition_id\":%d}}", opcode, transitionId);
        gatewaySender.sendTextFrame(json);
    }

    private void cleanup() {
        if (encryptor != null) {
            encryptor.close();
            encryptor = null;
        }
        if (decryptor != null) {
            decryptor.close();
            decryptor = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    public void close() {
        cleanup();
        state = State.INACTIVE;
        recognizedUserIds.clear();
    }
}
