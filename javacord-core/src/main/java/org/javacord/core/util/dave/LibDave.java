package org.javacord.core.util.dave;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA interface mapping the libdave C API for Discord's Audio/Video
 * End-to-End Encryption (DAVE) protocol.
 *
 * <p>All handles returned by {@code *Create} functions must be freed with
 * their corresponding {@code *Destroy} counterpart. Output byte arrays
 * allocated by the library must be freed using {@link #daveFree(Pointer)}.
 *
 * @see <a href="https://daveprotocol.com">DAVE Protocol Specification</a>
 */
public interface LibDave extends Library {

    // ---- Version ----

    /**
     * Returns the maximum DAVE protocol version supported by the loaded library.
     *
     * @return The maximum supported protocol version number.
     */
    short daveMaxSupportedProtocolVersion();

    // ---- Memory ----

    /**
     * Frees memory previously allocated by the DAVE library.
     *
     * @param ptr Pointer to memory allocated by a DAVE API function.
     */
    void daveFree(Pointer ptr);

    // ---- Session ----

    /**
     * Creates a new DAVE session.
     *
     * @param context       Platform-specific context pointer (can be {@code null}).
     * @param authSessionId String for persistent key lifetimes (can be {@code null}).
     * @param callback      Callback invoked on MLS failures (can be {@code null}).
     * @param userData      User data pointer passed to the callback.
     * @return New session handle, or {@code null} on failure.
     */
    Pointer daveSessionCreate(Pointer context, String authSessionId,
                              Pointer callback, Pointer userData);

    /**
     * Destroys a session and frees associated resources.
     *
     * @param session Session handle to destroy.
     */
    void daveSessionDestroy(Pointer session);

    /**
     * Initializes a session with protocol version and group information.
     *
     * @param session    Session handle.
     * @param version    Protocol version to use.
     * @param groupId    Group identifier (guild ID).
     * @param selfUserId User ID of the local user as a string.
     */
    void daveSessionInit(Pointer session, short version, long groupId, String selfUserId);

    /**
     * Resets the session state.
     *
     * @param session Session handle.
     */
    void daveSessionReset(Pointer session);

    /**
     * Sets the protocol version for the session.
     *
     * @param session Session handle.
     * @param version Protocol version to set.
     */
    void daveSessionSetProtocolVersion(Pointer session, short version);

    /**
     * Gets the current protocol version of the session.
     *
     * @param session Session handle.
     * @return Current protocol version.
     */
    short daveSessionGetProtocolVersion(Pointer session);

    /**
     * Retrieves the authenticator from the last MLS epoch.
     *
     * @param session       Session handle.
     * @param authenticator Output pointer to authenticator bytes (caller must free with daveFree).
     * @param length        Output pointer to authenticator length.
     */
    void daveSessionGetLastEpochAuthenticator(Pointer session,
                                              PointerByReference authenticator,
                                              IntByReference length);

    /**
     * Sets the external sender credentials for the session.
     *
     * @param session        Session handle.
     * @param externalSender External sender credential bytes.
     * @param length         Length of external sender data.
     */
    void daveSessionSetExternalSender(Pointer session, byte[] externalSender, int length);

    /**
     * Processes MLS proposals and generates commit/welcome messages.
     *
     * @param session                   Session handle.
     * @param proposals                 Serialized proposal bytes.
     * @param length                    Length of proposals.
     * @param recognizedUserIds         Array of recognized user ID strings.
     * @param recognizedUserIdsLength   Number of recognized user IDs.
     * @param commitWelcomeBytes        Output buffer for commit/welcome message bytes (caller must free with daveFree).
     * @param commitWelcomeBytesLength  Output length of the commit/welcome message.
     */
    void daveSessionProcessProposals(Pointer session,
                                     byte[] proposals, int length,
                                     String[] recognizedUserIds, int recognizedUserIdsLength,
                                     PointerByReference commitWelcomeBytes,
                                     IntByReference commitWelcomeBytesLength);

    /**
     * Processes an incoming MLS commit message.
     *
     * @param session Session handle.
     * @param commit  Serialized commit message bytes.
     * @param length  Length of commit message.
     * @return Commit result handle. Must be destroyed with {@link #daveCommitResultDestroy(Pointer)}.
     */
    Pointer daveSessionProcessCommit(Pointer session, byte[] commit, int length);

    /**
     * Processes an incoming MLS welcome message to join a group.
     *
     * @param session                 Session handle.
     * @param welcome                 Serialized welcome message bytes.
     * @param length                  Length of welcome message.
     * @param recognizedUserIds       Array of recognized user ID strings.
     * @param recognizedUserIdsLength Number of recognized user IDs.
     * @return Welcome result handle. Must be destroyed with {@link #daveWelcomeResultDestroy(Pointer)}.
     */
    Pointer daveSessionProcessWelcome(Pointer session,
                                      byte[] welcome, int length,
                                      String[] recognizedUserIds, int recognizedUserIdsLength);

    /**
     * Gets the marshalled MLS key package for this session.
     *
     * @param session    Session handle.
     * @param keyPackage Output buffer for key package bytes (caller must free with daveFree).
     * @param length     Output length of the key package.
     */
    void daveSessionGetMarshalledKeyPackage(Pointer session,
                                            PointerByReference keyPackage,
                                            IntByReference length);

    /**
     * Gets a key ratchet for a specific user in the session.
     *
     * @param session Session handle.
     * @param userId  User ID string to get key ratchet for.
     * @return Key ratchet handle. Must be destroyed with {@link #daveKeyRatchetDestroy(Pointer)}.
     */
    Pointer daveSessionGetKeyRatchet(Pointer session, String userId);

    // ---- Key Ratchet ----

    /**
     * Destroys a key ratchet and frees associated resources.
     *
     * @param keyRatchet Key ratchet handle to destroy.
     */
    void daveKeyRatchetDestroy(Pointer keyRatchet);

    // ---- Commit Result ----

    /**
     * Checks if processing the commit failed.
     *
     * @param commitResult Commit result handle.
     * @return {@code true} if commit processing failed.
     */
    boolean daveCommitResultIsFailed(Pointer commitResult);

    /**
     * Checks if the commit should be ignored.
     *
     * @param commitResult Commit result handle.
     * @return {@code true} if commit should be ignored.
     */
    boolean daveCommitResultIsIgnored(Pointer commitResult);

    /**
     * Gets the list of member IDs in the roster after the commit.
     *
     * @param commitResult   Commit result handle.
     * @param rosterIds      Output buffer for roster member IDs (caller must free with daveFree).
     * @param rosterIdsLength Output length of the roster member IDs array.
     */
    void daveCommitResultGetRosterMemberIds(Pointer commitResult,
                                            PointerByReference rosterIds,
                                            IntByReference rosterIdsLength);

    /**
     * Destroys a commit result and frees associated resources.
     *
     * @param commitResult Commit result handle to destroy.
     */
    void daveCommitResultDestroy(Pointer commitResult);

    // ---- Welcome Result ----

    /**
     * Gets the list of member IDs in the roster from the welcome message.
     *
     * @param welcomeResult   Welcome result handle.
     * @param rosterIds       Output buffer for roster member IDs (caller must free with daveFree).
     * @param rosterIdsLength Output length of the roster member IDs array.
     */
    void daveWelcomeResultGetRosterMemberIds(Pointer welcomeResult,
                                             PointerByReference rosterIds,
                                             IntByReference rosterIdsLength);

    /**
     * Destroys a welcome result and frees associated resources.
     *
     * @param welcomeResult Welcome result handle to destroy.
     */
    void daveWelcomeResultDestroy(Pointer welcomeResult);

    // ---- Encryptor ----

    /**
     * Creates a new media frame encryptor.
     *
     * @return New encryptor handle.
     */
    Pointer daveEncryptorCreate();

    /**
     * Destroys an encryptor and frees associated resources.
     *
     * @param encryptor Encryptor handle to destroy.
     */
    void daveEncryptorDestroy(Pointer encryptor);

    /**
     * Sets the key ratchet for encryption.
     *
     * @param encryptor  Encryptor handle.
     * @param keyRatchet Key ratchet to use for encryption.
     */
    void daveEncryptorSetKeyRatchet(Pointer encryptor, Pointer keyRatchet);

    /**
     * Enables or disables passthrough mode.
     *
     * @param encryptor      Encryptor handle.
     * @param passthroughMode {@code true} to enable passthrough, {@code false} to encrypt.
     */
    void daveEncryptorSetPassthroughMode(Pointer encryptor, boolean passthroughMode);

    /**
     * Associates an SSRC with a specific codec.
     *
     * @param encryptor Encryptor handle.
     * @param ssrc      SSRC identifier.
     * @param codecType Codec type for this SSRC (1=Opus, 2=VP8, etc.).
     */
    void daveEncryptorAssignSsrcToCodec(Pointer encryptor, int ssrc, int codecType);

    /**
     * Checks if the encryptor has a key ratchet.
     *
     * @param encryptor Encryptor handle.
     * @return {@code true} if has key ratchet.
     */
    boolean daveEncryptorHasKeyRatchet(Pointer encryptor);

    /**
     * Checks if the encryptor is in passthrough mode.
     *
     * @param encryptor Encryptor handle.
     * @return {@code true} if in passthrough mode.
     */
    boolean daveEncryptorIsPassthroughMode(Pointer encryptor);

    /**
     * Calculates the maximum ciphertext size for a given plaintext frame size.
     *
     * @param encryptor Encryptor handle.
     * @param mediaType Media type (0=Audio, 1=Video).
     * @param frameSize Size of plaintext frame in bytes.
     * @return Maximum possible ciphertext size in bytes.
     */
    int daveEncryptorGetMaxCiphertextByteSize(Pointer encryptor, int mediaType, int frameSize);

    /**
     * Encrypts a media frame.
     *
     * @param encryptor             Encryptor handle.
     * @param mediaType             Media type (0=Audio, 1=Video).
     * @param ssrc                  SSRC of the stream.
     * @param frame                 Pointer to plaintext frame data.
     * @param frameLength           Length of plaintext frame.
     * @param encryptedFrame        Pointer to the output buffer.
     * @param encryptedFrameCapacity Capacity of the output buffer.
     * @param bytesWritten          Number of bytes written to the output buffer.
     * @return Result code (0=Success).
     */
    int daveEncryptorEncrypt(Pointer encryptor, int mediaType, int ssrc,
                             byte[] frame, int frameLength,
                             byte[] encryptedFrame, int encryptedFrameCapacity,
                             IntByReference bytesWritten);

    // ---- Decryptor ----

    /**
     * Creates a new media frame decryptor.
     *
     * @return New decryptor handle.
     */
    Pointer daveDecryptorCreate();

    /**
     * Destroys a decryptor and frees associated resources.
     *
     * @param decryptor Decryptor handle to destroy.
     */
    void daveDecryptorDestroy(Pointer decryptor);

    /**
     * Transitions the decryptor to use a new key ratchet.
     *
     * @param decryptor  Decryptor handle.
     * @param keyRatchet New key ratchet to transition to.
     */
    void daveDecryptorTransitionToKeyRatchet(Pointer decryptor, Pointer keyRatchet);

    /**
     * Transitions to or from passthrough mode.
     *
     * @param decryptor       Decryptor handle.
     * @param passthroughMode {@code true} to enable passthrough.
     */
    void daveDecryptorTransitionToPassthroughMode(Pointer decryptor, boolean passthroughMode);

    /**
     * Decrypts an encrypted media frame.
     *
     * @param decryptor             Decryptor handle.
     * @param mediaType             Media type (0=Audio, 1=Video).
     * @param encryptedFrame        Pointer to the encrypted frame data.
     * @param encryptedFrameLength  Length of the encrypted frame.
     * @param frame                 Pointer to the output buffer.
     * @param frameCapacity         Capacity of the output buffer.
     * @param bytesWritten          Number of bytes written to the output buffer.
     * @return Result code (0=Success).
     */
    int daveDecryptorDecrypt(Pointer decryptor, int mediaType,
                             byte[] encryptedFrame, int encryptedFrameLength,
                             byte[] frame, int frameCapacity,
                             IntByReference bytesWritten);

    /**
     * Calculates the maximum plaintext size for a given ciphertext frame size.
     *
     * @param decryptor          Decryptor handle.
     * @param mediaType          Media type (0=Audio, 1=Video).
     * @param encryptedFrameSize Size of encrypted frame in bytes.
     * @return Maximum possible plaintext size in bytes.
     */
    int daveDecryptorGetMaxPlaintextByteSize(Pointer decryptor, int mediaType, int encryptedFrameSize);
}
