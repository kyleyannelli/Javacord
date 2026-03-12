package org.javacord.core.util.dave;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.apache.logging.log4j.Logger;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.Optional;

/**
 * Wraps the native DAVE frame decryptor for E2EE audio frame decryption.
 *
 * <p>Decrypts incoming DAVE-encrypted frames using per-sender key ratchets
 * derived from the MLS group state. Supports transition between key epochs
 * with temporary retention of old keys. The native handle is freed on close.
 */
public class DaveFrameDecryptor implements AutoCloseable {

    private static final Logger logger = LoggerUtil.getLogger(DaveFrameDecryptor.class);

    private static final int MEDIA_TYPE_AUDIO = 0;

    private final LibDave lib;
    private Pointer decryptorHandle;

    /**
     * Creates a new DAVE frame decryptor.
     *
     * @param lib The loaded libdave native interface.
     */
    public DaveFrameDecryptor(LibDave lib) {
        this.lib = lib;
        this.decryptorHandle = lib.daveDecryptorCreate();
        if (decryptorHandle == null) {
            throw new IllegalStateException("Failed to create native DAVE frame decryptor");
        }
    }

    /**
     * Transitions the decryptor to use a new key ratchet.
     *
     * <p>The previous key ratchet is temporarily retained for up to ten seconds
     * to decrypt in-flight frames from the previous epoch.
     *
     * @param keyRatchetHandle The native key ratchet handle from the DAVE session.
     */
    public void transitionToKeyRatchet(Pointer keyRatchetHandle) {
        ensureOpen();
        lib.daveDecryptorTransitionToKeyRatchet(decryptorHandle, keyRatchetHandle);
    }

    /**
     * Transitions to or from passthrough mode.
     *
     * <p>In passthrough mode, encrypted frames are passed through without
     * decryption. Previous key ratchets are temporarily retained during
     * the transition.
     *
     * @param passthrough {@code true} to enable passthrough mode.
     */
    public void transitionToPassthroughMode(boolean passthrough) {
        ensureOpen();
        lib.daveDecryptorTransitionToPassthroughMode(decryptorHandle, passthrough);
    }

    /**
     * Decrypts a DAVE-encrypted audio frame.
     *
     * @param encryptedFrame The encrypted frame bytes in DAVE payload format.
     * @return The decrypted Opus audio frame, or empty if decryption failed.
     */
    public Optional<byte[]> decrypt(byte[] encryptedFrame) {
        ensureOpen();
        int maxSize = lib.daveDecryptorGetMaxPlaintextByteSize(
                decryptorHandle, MEDIA_TYPE_AUDIO, encryptedFrame.length);
        byte[] output = new byte[maxSize];
        IntByReference bytesWritten = new IntByReference();

        int resultCode = lib.daveDecryptorDecrypt(
                decryptorHandle, MEDIA_TYPE_AUDIO,
                encryptedFrame, encryptedFrame.length,
                output, output.length,
                bytesWritten);

        if (resultCode != 0) {
            logger.trace("DAVE frame decryption returned result code {}", resultCode);
            return Optional.empty();
        }

        int written = bytesWritten.getValue();
        if (written <= 0) {
            return Optional.empty();
        }

        byte[] decrypted = new byte[written];
        System.arraycopy(output, 0, decrypted, 0, written);
        return Optional.of(decrypted);
    }

    private void ensureOpen() {
        if (decryptorHandle == null) {
            throw new IllegalStateException("DAVE frame decryptor has already been closed");
        }
    }

    @Override
    public void close() {
        if (decryptorHandle != null) {
            lib.daveDecryptorDestroy(decryptorHandle);
            decryptorHandle = null;
        }
    }
}
