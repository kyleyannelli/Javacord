package org.javacord.core.util.dave;

import com.sun.jna.Pointer;
import org.apache.logging.log4j.Logger;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Orchestrates the full DAVE E2EE protocol lifecycle for a single voice connection.
 *
 * <p>Manages the MLS session state machine, frame encryptor/decryptor, recognized
 * user tracking, and transitions. All DAVE voice gateway opcodes are routed
 * through this manager.
 *
 * <p>The state machine progresses through:
 * <ul>
 *   <li>{@link State#INACTIVE} - Not initialized, or closed</li>
 *   <li>{@link State#AWAITING_GROUP} - Key package sent, waiting for a welcome or our own commit</li>
 *   <li>{@link State#TRANSITIONING} - Group updated, waiting for the gateway to execute the transition</li>
 *   <li>{@link State#ESTABLISHED} - Sending with the current epoch's key ratchet</li>
 *   <li>{@link State#PASSTHROUGH} - Downgraded to protocol version 0, frames pass through</li>
 * </ul>
 *
 * <p>Voice readiness never waits for this state machine: as in the reference clients, audio may be
 * sent right after the session description and is passed through until a transition executes.
 *
 * <p>Methods are synchronized: gateway messages arrive on the websocket thread, but {@link #close()}
 * may be called from any thread.
 *
 * @see <a href="https://daveprotocol.com">DAVE Protocol Specification</a>
 */
public class DaveSessionManager implements AutoCloseable {

    private static final Logger logger = LoggerUtil.getLogger(DaveSessionManager.class);

    /**
     * Transition ID 0 means (re)initialization: execute immediately, never send transition ready.
     */
    private static final int INIT_TRANSITION_ID = 0;

    private static final int OP_TRANSITION_READY = 23;
    private static final int OP_MLS_KEY_PACKAGE = 26;
    private static final int OP_MLS_COMMIT_WELCOME = 28;
    private static final int OP_MLS_INVALID_COMMIT_WELCOME = 31;

    /**
     * The possible states of the DAVE session lifecycle.
     */
    public enum State {
        INACTIVE,
        AWAITING_GROUP,
        TRANSITIONING,
        ESTABLISHED,
        PASSTHROUGH
    }

    private final LongSupplier groupIdSupplier;
    private final String selfUserId;
    private final VoiceGatewaySender gatewaySender;

    private final LibDave lib;
    private DaveSession session;
    private DaveFrameEncryptor encryptor;
    private DaveFrameDecryptor decryptor;

    private volatile State state = State.INACTIVE;
    private int protocolVersion;

    /**
     * Announced but not yet executed transitions: transition ID to the protocol version it moves to.
     */
    private final Map<Integer, Integer> pendingTransitions = new HashMap<>();

    /**
     * Set while recovering from an invalid commit/welcome, so a burst of failures sends op 31 only once.
     */
    private boolean reinitializing;

    /**
     * Users in the media session (opcode 11). Add proposals for anyone else are refused.
     */
    private final Set<String> recognizedUserIds = new HashSet<>();

    /**
     * Users who left (opcode 13) and have not reconnected. They can still be group members until their
     * removal is committed, so a welcome that lists them is accepted; adding them again is not.
     * Cleared whenever a commit or welcome is applied, so this tolerance lasts only until the group moves on.
     */
    private final Set<String> disconnectedUserIds = new HashSet<>();

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
     * @param groupIdSupplier Supplies the MLS group ID: the current voice channel ID. Read on every
     *                        (re)initialization, so a moved connection joins the new channel's group.
     * @param selfUserId      The bot's own user ID as a string.
     * @param gatewaySender   Callback for sending voice gateway messages.
     */
    public DaveSessionManager(LongSupplier groupIdSupplier, String selfUserId, VoiceGatewaySender gatewaySender) {
        this(groupIdSupplier, selfUserId, gatewaySender, LibDaveLoader.getInstance());
    }

    DaveSessionManager(LongSupplier groupIdSupplier, String selfUserId, VoiceGatewaySender gatewaySender,
                       LibDave lib) {
        this.groupIdSupplier = groupIdSupplier;
        this.selfUserId = selfUserId;
        this.gatewaySender = gatewaySender;
        this.lib = lib;
        recognizedUserIds.add(selfUserId);
    }

    /**
     * Initializes the DAVE session after receiving a non-zero {@code dave_protocol_version}
     * in the SESSION_DESCRIPTION (opcode 4) response, and sends our key package.
     *
     * @param protocolVersion The DAVE protocol version selected by the voice gateway.
     * @param ssrc            The SSRC assigned to this client.
     */
    public synchronized void initialize(int protocolVersion, int ssrc) {
        if (lib == null) {
            logger.error("Cannot initialize DAVE session: libdave not available. "
                    + "Install libdave on your system to use voice channels with E2EE.");
            state = State.INACTIVE;
            return;
        }

        cleanup();
        session = new DaveSession(lib);
        encryptor = new DaveFrameEncryptor(lib);
        encryptor.assignSsrcToOpus(ssrc);
        decryptor = new DaveFrameDecryptor(lib);
        decryptor.transitionToPassthroughMode(true);

        this.protocolVersion = protocolVersion;
        reinit();
        logger.debug("DAVE session initialized with protocol version {} for group {}",
                protocolVersion, groupIdSupplier.getAsLong());
    }

    /**
     * Handles the MLS external sender package (opcode 25, binary).
     *
     * <p>libdave keeps the external sender across re-initializations and creates its pending
     * group from it.
     *
     * @param externalSenderData The raw binary payload after the opcode/sequence header.
     */
    public synchronized void handleExternalSender(byte[] externalSenderData) {
        if (session == null) {
            return;
        }
        if (state == State.ESTABLISHED || state == State.TRANSITIONING) {
            // libdave refuses to replace the external sender of an existing group
            logger.debug("Ignoring external sender for group {}: already in an MLS group [state={}]",
                    groupIdSupplier.getAsLong(), state);
            return;
        }
        session.setExternalSender(externalSenderData);
        logger.debug("Set external sender ({} bytes) for group {} [state={}]",
                externalSenderData.length, groupIdSupplier.getAsLong(), state);
    }

    /**
     * Handles MLS proposals from the voice gateway (opcode 27, binary).
     *
     * <p>Processes the proposals against the pending or established group and, if a commit
     * results, sends it (opcode 28). This is also how the first member creates the group.
     *
     * @param proposalsData The raw binary proposals payload.
     */
    public synchronized void handleProposals(byte[] proposalsData) {
        if (session == null || state == State.INACTIVE) {
            return;
        }
        Optional<byte[]> commitWelcome = session.processProposals(proposalsData, recognizedUserIds);
        commitWelcome.ifPresent(bytes -> {
            sendBinaryMessage(OP_MLS_COMMIT_WELCOME, bytes);
            logger.debug("Processed proposals and sent commit/welcome for group {}", groupIdSupplier.getAsLong());
        });
    }

    /**
     * Handles an MLS commit transition from the voice gateway (opcode 29, binary).
     *
     * <p>The payload format after stripping the 3-byte server header is:
     * {@code [uint16 transition_id][MLS commit data...]}.
     *
     * @param payload The binary payload containing transition ID and MLS commit data.
     */
    public synchronized void handleCommitTransition(byte[] payload) {
        if (session == null || payload.length < 2) {
            return;
        }
        int transitionId = readTransitionId(payload);
        byte[] commitData = new byte[payload.length - 2];
        System.arraycopy(payload, 2, commitData, 0, commitData.length);

        try (DaveCommitResult result = session.processCommit(commitData)) {
            if (result.isIgnored()) {
                // libdave: a commit that arrives while we have no state at all (e.g. mid-reset);
                // requesting a reset for it would only cause thrashing
                logger.debug("Ignoring MLS commit for transition {} in group {} [state={}]",
                        transitionId, groupIdSupplier.getAsLong(), state);
                return;
            }
            if (result.isFailed()) {
                logger.warn("Failed to process MLS commit for transition {} in group {}, requesting re-add "
                        + "[state={}]", transitionId, groupIdSupplier.getAsLong(), state);
                recoverFromInvalidCommitWelcome(transitionId);
                return;
            }
        }
        onGroupUpdated(transitionId);
    }

    /**
     * Handles an MLS welcome message (opcode 30, binary).
     *
     * <p>The payload format after stripping the 3-byte server header is:
     * {@code [uint16 transition_id][MLS welcome data...]}.
     *
     * @param payload The binary payload containing transition ID and MLS welcome data.
     */
    public synchronized void handleWelcome(byte[] payload) {
        if (session == null || payload.length < 2) {
            return;
        }
        int transitionId = readTransitionId(payload);
        byte[] welcome = new byte[payload.length - 2];
        System.arraycopy(payload, 2, welcome, 0, welcome.length);

        Set<String> knownMembers = new HashSet<>(recognizedUserIds);
        knownMembers.addAll(disconnectedUserIds);
        try (DaveWelcomeResult result = session.processWelcome(welcome, knownMembers)) {
            if (result.isFailed()) {
                logger.warn("Failed to process MLS welcome for transition {} in group {}, requesting re-add "
                        + "[state={}, recognizedUsers={}]",
                        transitionId, groupIdSupplier.getAsLong(), state, recognizedUserIds);
                recoverFromInvalidCommitWelcome(transitionId);
                return;
            }
        }
        onGroupUpdated(transitionId);
    }

    /**
     * Handles the prepare transition opcode (opcode 21, JSON).
     *
     * <p>Announces a protocol version change. A downgrade to version 0 immediately lets the
     * receive side accept unencrypted frames; the send side switches when the transition executes.
     *
     * @param transitionProtocolVersion The target protocol version.
     * @param transitionId              The transition ID.
     */
    public synchronized void handlePrepareTransition(int transitionProtocolVersion, int transitionId) {
        if (session == null) {
            return;
        }
        if (transitionProtocolVersion == 0) {
            decryptor.transitionToPassthroughMode(true);
        }
        if (transitionId == INIT_TRANSITION_ID) {
            executeTransition(transitionId, transitionProtocolVersion);
        } else {
            pendingTransitions.put(transitionId, transitionProtocolVersion);
            sendTransitionMessage(OP_TRANSITION_READY, transitionId);
        }
        logger.debug("Prepared transition {} to protocol version {} for group {}",
                transitionId, transitionProtocolVersion, groupIdSupplier.getAsLong());
    }

    /**
     * Handles the execute transition opcode (opcode 22, JSON).
     *
     * @param transitionId The transition ID to execute.
     */
    public synchronized void handleExecuteTransition(int transitionId) {
        if (session == null) {
            return;
        }
        Integer version = pendingTransitions.remove(transitionId);
        if (version == null) {
            logger.warn("Received execute for unknown transition {} in group {} [pending={}, state={}]",
                    transitionId, groupIdSupplier.getAsLong(), pendingTransitions.keySet(), state);
            return;
        }
        executeTransition(transitionId, version);
    }

    /**
     * Handles the prepare epoch opcode (opcode 24, JSON).
     *
     * <p>Epoch 1 means a new group: reset local MLS state and send a fresh key package.
     * Later epochs need no action here; the group moves on through commits.
     *
     * @param epoch           The epoch ID.
     * @param protocolVersion The protocol version for the new epoch.
     */
    public synchronized void handlePrepareEpoch(long epoch, int protocolVersion) {
        if (session == null) {
            return;
        }
        if (epoch == 1) {
            this.protocolVersion = protocolVersion;
            reinit();
        } else {
            logger.debug("Prepare epoch {} (protocol version {}) for group {} [state={}]",
                    epoch, protocolVersion, groupIdSupplier.getAsLong(), state);
        }
    }

    /**
     * Registers a user as a recognized voice session participant (opcode 11, or the channel cache).
     *
     * @param userId The user ID string.
     */
    public synchronized void addRecognizedUser(String userId) {
        recognizedUserIds.add(userId);
        disconnectedUserIds.remove(userId);
    }

    /**
     * Removes a user from the recognized voice session participants (opcode 13).
     *
     * @param userId The user ID string.
     */
    public synchronized void removeRecognizedUser(String userId) {
        if (!userId.equals(selfUserId) && recognizedUserIds.remove(userId)) {
            disconnectedUserIds.add(userId);
        }
    }

    /**
     * Gets the DAVE frame encryptor for use in the audio send pipeline.
     *
     * @return The encryptor, or {@code null} if DAVE is not active.
     */
    public synchronized DaveFrameEncryptor getEncryptor() {
        return encryptor;
    }

    /**
     * Gets the DAVE frame decryptor for use in a future audio receive pipeline.
     *
     * @return The decryptor, or {@code null} if DAVE is not active.
     */
    public synchronized DaveFrameDecryptor getDecryptor() {
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
    public synchronized boolean isEncryptionActive() {
        return state == State.ESTABLISHED
                && encryptor != null
                && encryptor.hasKeyRatchet()
                && !encryptor.isPassthroughMode();
    }

    /**
     * Returns whether DAVE E2EE is negotiated for this session (libdave loaded and protocol version non-zero).
     *
     * @return {@code true} if DAVE is active for this session.
     */
    public synchronized boolean isDaveActive() {
        return session != null && protocolVersion > 0;
    }

    /**
     * Called after a commit or welcome was applied: the transition either executes now (ID 0)
     * or once the gateway sends execute transition (opcode 22).
     */
    private void onGroupUpdated(int transitionId) {
        reinitializing = false;
        disconnectedUserIds.clear();
        if (transitionId == INIT_TRANSITION_ID) {
            executeTransition(transitionId, protocolVersion);
        } else {
            pendingTransitions.put(transitionId, protocolVersion);
            state = State.TRANSITIONING;
            sendTransitionMessage(OP_TRANSITION_READY, transitionId);
            logger.debug("MLS group updated, sent transition ready for {} in group {}",
                    transitionId, groupIdSupplier.getAsLong());
        }
    }

    /**
     * Switches the send side to the given protocol version: the current epoch's key ratchet,
     * or passthrough for version 0.
     */
    private void executeTransition(int transitionId, int version) {
        State oldState = state;
        protocolVersion = version;
        reinitializing = false;

        if (version == 0) {
            encryptor.setPassthroughMode(true);
            decryptor.transitionToPassthroughMode(true);
            state = State.PASSTHROUGH;
            logger.info("DAVE downgraded to passthrough (transition {}) for group {}",
                    transitionId, groupIdSupplier.getAsLong());
            return;
        }

        Pointer selfRatchet = session.getKeyRatchet(selfUserId);
        if (selfRatchet == null) {
            // No established group (e.g. an initial transition before any welcome): keep passing through
            logger.warn("Cannot execute transition {} for group {}: no MLS group yet, staying in passthrough "
                    + "[state={}]", transitionId, groupIdSupplier.getAsLong(), state);
            return;
        }
        try {
            encryptor.setKeyRatchet(selfRatchet);
        } finally {
            session.destroyKeyRatchet(selfRatchet);
        }
        encryptor.setPassthroughMode(false);
        // receivers keep accepting unencrypted frames for a short grace period, then require DAVE
        decryptor.transitionToPassthroughMode(false);
        state = State.ESTABLISHED;

        if (oldState != State.ESTABLISHED) {
            logger.info("DAVE E2EE established (transition {}, protocol version {}) for group {}",
                    transitionId, version, groupIdSupplier.getAsLong());
        } else {
            logger.debug("Executed transition {} for group {}", transitionId, groupIdSupplier.getAsLong());
        }
    }

    /**
     * Requests removal and re-add after a commit or welcome we could not process (opcode 31),
     * then starts over with a fresh key package.
     */
    private void recoverFromInvalidCommitWelcome(int transitionId) {
        if (reinitializing) {
            logger.debug("Already recovering, not sending another invalid commit/welcome for transition {}",
                    transitionId);
            return;
        }
        reinitializing = true;
        sendTransitionMessage(OP_MLS_INVALID_COMMIT_WELCOME, transitionId);
        reinit();
    }

    /**
     * Discards local MLS state and, for a non-zero protocol version, sends a fresh key package.
     *
     * <p>Uses {@link DaveSession#init}: libdave's reset alone also discards the leaf node and
     * group ID, after which no key package can be generated. Until a transition executes, frames
     * pass through, matching the reference clients while not in a group.
     */
    private void reinit() {
        pendingTransitions.clear();
        encryptor.setPassthroughMode(true);

        if (protocolVersion == 0) {
            session.reset();
            decryptor.transitionToPassthroughMode(true);
            state = State.PASSTHROUGH;
            return;
        }

        session.init(protocolVersion, groupIdSupplier.getAsLong(), selfUserId);
        Optional<byte[]> keyPackage = session.getMarshalledKeyPackage();
        if (keyPackage.isPresent()) {
            sendBinaryMessage(OP_MLS_KEY_PACKAGE, keyPackage.get());
            logger.debug("Sent key package ({} bytes) for group {}",
                    keyPackage.get().length, groupIdSupplier.getAsLong());
        } else {
            logger.error("Failed to generate DAVE key package for group {}; E2EE cannot be established",
                    groupIdSupplier.getAsLong());
        }
        state = State.AWAITING_GROUP;
    }

    private static int readTransitionId(byte[] payload) {
        return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    private void sendBinaryMessage(int opcode, byte[] data) {
        byte[] frame = new byte[data.length + 1];
        frame[0] = (byte) opcode;
        System.arraycopy(data, 0, frame, 1, data.length);
        gatewaySender.sendBinaryFrame(frame);
    }

    private void sendTransitionMessage(int opcode, int transitionId) {
        gatewaySender.sendTextFrame(
                String.format("{\"op\":%d,\"d\":{\"transition_id\":%d}}", opcode, transitionId));
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
        pendingTransitions.clear();
        reinitializing = false;
    }

    @Override
    public synchronized void close() {
        cleanup();
        state = State.INACTIVE;
        recognizedUserIds.clear();
        recognizedUserIds.add(selfUserId);
        disconnectedUserIds.clear();
    }
}
