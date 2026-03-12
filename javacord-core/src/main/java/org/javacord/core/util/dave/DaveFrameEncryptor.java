package org.javacord.core.util.dave;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.apache.logging.log4j.Logger;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.Optional;

/**
 * Wraps the native DAVE frame encryptor for E2EE audio frame encryption.
 *
 * <p>After the MLS group is established and a sender key ratchet is derived,
 * this encryptor transforms raw Opus frames into DAVE-encrypted frames before
 * they undergo transport encryption. The native handle is freed on close.
 */
public class DaveFrameEncryptor implements AutoCloseable {

    private static final Logger logger = LoggerUtil.getLogger(DaveFrameEncryptor.class);

    private static final int MEDIA_TYPE_AUDIO = 0;
    private static final int CODEC_OPUS = 1;

    private final LibDave lib;
    private Pointer encryptorHandle;

    /**
     * Creates a new DAVE frame encryptor.
     *
     * @param lib The loaded libdave native interface.
     */
    public DaveFrameEncryptor(LibDave lib) {
        this.lib = lib;
        this.encryptorHandle = lib.daveEncryptorCreate();
        if (encryptorHandle == null) {
            throw new IllegalStateException("Failed to create native DAVE frame encryptor");
        }
    }

    /**
     * Sets the key ratchet for encrypting outgoing audio frames.
     *
     * <p>The key ratchet is derived from the MLS group state and provides
     * per-sender symmetric encryption keys. Must be updated on each epoch transition.
     *
     * @param keyRatchetHandle The native key ratchet handle from the DAVE session.
     */
    public void setKeyRatchet(Pointer keyRatchetHandle) {
        ensureOpen();
        lib.daveEncryptorSetKeyRatchet(encryptorHandle, keyRatchetHandle);
    }

    /**
     * Enables or disables passthrough mode.
     *
     * <p>In passthrough mode, frames pass through unencrypted. This is used
     * during transitions and when the media session is not E2EE.
     *
     * @param passthrough {@code true} to pass frames through unencrypted.
     */
    public void setPassthroughMode(boolean passthrough) {
        ensureOpen();
        lib.daveEncryptorSetPassthroughMode(encryptorHandle, passthrough);
    }

    /**
     * Registers an SSRC as sending Opus audio.
     *
     * @param ssrc The synchronization source identifier.
     */
    public void assignSsrcToOpus(int ssrc) {
        ensureOpen();
        lib.daveEncryptorAssignSsrcToCodec(encryptorHandle, ssrc, CODEC_OPUS);
    }

    /**
     * Returns whether this encryptor has an active key ratchet.
     *
     * @return {@code true} if a key ratchet is set and ready for encryption.
     */
    public boolean hasKeyRatchet() {
        ensureOpen();
        return lib.daveEncryptorHasKeyRatchet(encryptorHandle);
    }

    /**
     * Returns whether this encryptor is in passthrough mode.
     *
     * @return {@code true} if frames are passing through unencrypted.
     */
    public boolean isPassthroughMode() {
        ensureOpen();
        return lib.daveEncryptorIsPassthroughMode(encryptorHandle);
    }

    /**
     * Encrypts a raw audio frame using the DAVE protocol.
     *
     * <p>The output frame includes the DAVE payload format: encrypted data,
     * truncated AES-128-GCM authentication tag, ULEB128 nonce, unencrypted
     * range descriptors, supplemental size, and the 0xFAFA magic marker.
     *
     * @param ssrc  The SSRC of the audio stream.
     * @param frame The raw Opus audio frame bytes.
     * @return The DAVE-encrypted frame, or empty if encryption failed or is in passthrough mode.
     */
    public Optional<byte[]> encrypt(int ssrc, byte[] frame) {
        ensureOpen();
        int maxSize = lib.daveEncryptorGetMaxCiphertextByteSize(encryptorHandle, MEDIA_TYPE_AUDIO, frame.length);
        byte[] output = new byte[maxSize];
        IntByReference bytesWritten = new IntByReference();

        int resultCode = lib.daveEncryptorEncrypt(
                encryptorHandle, MEDIA_TYPE_AUDIO, ssrc,
                frame, frame.length,
                output, output.length,
                bytesWritten);

        if (resultCode != 0) {
            logger.debug("DAVE frame encryption returned result code {}", resultCode);
            return Optional.empty();
        }

        int written = bytesWritten.getValue();
        if (written <= 0) {
            return Optional.empty();
        }

        byte[] encrypted = new byte[written];
        System.arraycopy(output, 0, encrypted, 0, written);
        return Optional.of(encrypted);
    }

    private void ensureOpen() {
        if (encryptorHandle == null) {
            throw new IllegalStateException("DAVE frame encryptor has already been closed");
        }
    }

    @Override
    public void close() {
        if (encryptorHandle != null) {
            lib.daveEncryptorDestroy(encryptorHandle);
            encryptorHandle = null;
        }
    }
}
