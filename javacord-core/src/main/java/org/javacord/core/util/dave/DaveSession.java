package org.javacord.core.util.dave;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import org.apache.logging.log4j.Logger;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.Optional;
import java.util.Set;

/**
 * Manages a native DAVE MLS session handle.
 *
 * <p>Wraps the libdave C API session lifecycle, providing safe Java methods
 * for all MLS operations required by the DAVE protocol. The native handle
 * is automatically freed when this object is closed.
 */
public class DaveSession implements AutoCloseable {

    private static final Logger logger = LoggerUtil.getLogger(DaveSession.class);

    private final LibDave lib;
    private Pointer sessionHandle;
    private boolean closed;

    /**
     * Creates a new DAVE session backed by the given libdave instance.
     *
     * @param lib The loaded libdave native interface.
     */
    public DaveSession(LibDave lib) {
        this.lib = lib;
        this.sessionHandle = lib.daveSessionCreate(null, null, null, null);
        if (sessionHandle == null) {
            throw new IllegalStateException("Failed to create native DAVE session");
        }
    }

    /**
     * Initializes the session with protocol version and group information.
     *
     * <p>Also resets any previous state: libdave's init clears the group and regenerates the
     * local leaf node, keeping only the external sender. Use this (not {@link #reset()}) to start
     * over and produce a fresh key package.
     *
     * @param protocolVersion The DAVE protocol version to use.
     * @param groupId         The MLS group ID: the voice channel ID.
     * @param selfUserId      The bot's own user ID as a string.
     */
    public void init(int protocolVersion, long groupId, String selfUserId) {
        ensureOpen();
        lib.daveSessionInit(sessionHandle, (short) protocolVersion, groupId, selfUserId);
    }

    /**
     * Resets all session state, invalidating any existing MLS group.
     *
     * <p>This also discards the local leaf node and group ID, so no key package can be produced
     * until {@link #init(int, long, String)} is called again.
     */
    public void reset() {
        ensureOpen();
        lib.daveSessionReset(sessionHandle);
    }

    /**
     * Updates the protocol version for an active session.
     *
     * @param protocolVersion The new DAVE protocol version.
     */
    public void setProtocolVersion(int protocolVersion) {
        ensureOpen();
        lib.daveSessionSetProtocolVersion(sessionHandle, (short) protocolVersion);
    }

    /**
     * Gets the current protocol version of the session.
     *
     * @return The current protocol version.
     */
    public int getProtocolVersion() {
        ensureOpen();
        return lib.daveSessionGetProtocolVersion(sessionHandle);
    }

    /**
     * Sets the MLS external sender credentials received from the voice gateway.
     *
     * @param externalSenderPackage The raw binary external sender package (from opcode 25).
     */
    public void setExternalSender(byte[] externalSenderPackage) {
        ensureOpen();
        lib.daveSessionSetExternalSender(sessionHandle, externalSenderPackage, (long) externalSenderPackage.length);
    }

    /**
     * Gets the marshalled MLS key package to send to the voice gateway.
     *
     * @return The serialized key package bytes, or an empty optional if generation failed.
     */
    public Optional<byte[]> getMarshalledKeyPackage() {
        ensureOpen();
        PointerByReference keyPackagePtr = new PointerByReference();
        LongByReference lengthRef = new LongByReference();
        lib.daveSessionGetMarshalledKeyPackage(sessionHandle, keyPackagePtr, lengthRef);
        return takeBuffer(keyPackagePtr, lengthRef);
    }

    /**
     * Processes MLS proposals received from the voice gateway and optionally generates
     * a commit/welcome message to send back.
     *
     * @param proposals       The raw binary proposals (from opcode 27).
     * @param recognizedUsers The set of user IDs currently recognized in the voice session.
     * @return The commit/welcome bytes to send (via opcode 28), or empty if no commit is needed.
     */
    public Optional<byte[]> processProposals(byte[] proposals, Set<String> recognizedUsers) {
        ensureOpen();
        String[] userIds = recognizedUsers.toArray(new String[0]);
        PointerByReference commitPtr = new PointerByReference();
        LongByReference lengthRef = new LongByReference();

        lib.daveSessionProcessProposals(
                sessionHandle, proposals, proposals.length,
                userIds, userIds.length,
                commitPtr, lengthRef);
        return takeBuffer(commitPtr, lengthRef);
    }

    /**
     * Processes an incoming MLS commit message from the voice gateway.
     *
     * @param commitData The raw binary commit message (from opcode 29).
     * @return The result of processing the commit.
     */
    public DaveCommitResult processCommit(byte[] commitData) {
        ensureOpen();
        Pointer resultHandle = lib.daveSessionProcessCommit(sessionHandle, commitData, (long) commitData.length);
        if (resultHandle == null) {
            return DaveCommitResult.failed();
        }
        return new DaveCommitResult(lib, resultHandle);
    }

    /**
     * Processes an incoming MLS welcome message to join an existing group.
     *
     * @param welcomeData     The raw binary welcome message (from opcode 30).
     * @param recognizedUsers The set of user IDs currently recognized in the voice session.
     * @return The result of processing the welcome.
     */
    public DaveWelcomeResult processWelcome(byte[] welcomeData, Set<String> recognizedUsers) {
        ensureOpen();
        String[] userIds = recognizedUsers.toArray(new String[0]);
        Pointer resultHandle = lib.daveSessionProcessWelcome(
                sessionHandle, welcomeData, welcomeData.length,
                userIds, userIds.length);
        if (resultHandle == null) {
            return DaveWelcomeResult.failed();
        }
        return new DaveWelcomeResult(lib, resultHandle);
    }

    /**
     * Gets a key ratchet for the specified user from the current MLS epoch.
     *
     * <p>The caller owns the returned handle and must free it with {@link #destroyKeyRatchet(Pointer)}.
     * Encryptors and decryptors copy ratchets, so it can be destroyed as soon as it has been handed over.
     *
     * @param userId The user ID string to get a key ratchet for.
     * @return The native key ratchet handle, or {@code null} if there is no established group.
     */
    public Pointer getKeyRatchet(String userId) {
        ensureOpen();
        return lib.daveSessionGetKeyRatchet(sessionHandle, userId);
    }

    /**
     * Frees a key ratchet obtained from {@link #getKeyRatchet(String)}.
     *
     * @param keyRatchet The key ratchet handle, may be {@code null}.
     */
    public void destroyKeyRatchet(Pointer keyRatchet) {
        if (keyRatchet != null) {
            lib.daveKeyRatchetDestroy(keyRatchet);
        }
    }

    /**
     * Retrieves the epoch authenticator for the current MLS epoch.
     *
     * @return The epoch authenticator bytes, or empty if not available.
     */
    public Optional<byte[]> getLastEpochAuthenticator() {
        ensureOpen();
        PointerByReference authPtr = new PointerByReference();
        LongByReference lengthRef = new LongByReference();
        lib.daveSessionGetLastEpochAuthenticator(sessionHandle, authPtr, lengthRef);
        return takeBuffer(authPtr, lengthRef);
    }

    /**
     * Copies a libdave-allocated output buffer into a Java array and frees it, even when empty.
     */
    private Optional<byte[]> takeBuffer(PointerByReference bufferRef, LongByReference lengthRef) {
        Pointer ptr = bufferRef.getValue();
        if (ptr == null) {
            return Optional.empty();
        }
        try {
            long length = lengthRef.getValue();
            return length > 0 ? Optional.of(ptr.getByteArray(0, (int) length)) : Optional.empty();
        } finally {
            lib.daveFree(ptr);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("DAVE session has already been closed");
        }
    }

    @Override
    public void close() {
        if (!closed && sessionHandle != null) {
            lib.daveSessionDestroy(sessionHandle);
            sessionHandle = null;
            closed = true;
        }
    }
}
