package org.javacord.core.util.dave

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import spock.lang.Specification
import spock.lang.Subject

import java.util.function.LongSupplier

/**
 * Behaviour follows the DAVE protocol (daveprotocol.com) and the reference clients
 * (discord.js DAVESession, discord.py), with libdave's own semantics for commit results.
 */
@Subject(DaveSessionManager)
class DaveSessionManagerTest extends Specification {

    static final Byte PT_ON = 1 as Byte
    static final Byte PT_OFF = 0 as Byte

    static final long CHANNEL_ID = 1184647280066822234L
    static final String SELF_USER_ID = '987654321'
    static final String OTHER_USER_ID = '111111111'
    static final int SSRC = 12345
    static final int PROTOCOL_VERSION = 1
    static final Short NATIVE_PROTOCOL_VERSION = 1 as Short
    static final Pointer FAKE_SESSION_HANDLE = new Pointer(1L)
    static final Pointer FAKE_ENCRYPTOR_HANDLE = new Pointer(2L)
    static final Pointer FAKE_DECRYPTOR_HANDLE = new Pointer(3L)
    static final Pointer FAKE_KEY_RATCHET = new Pointer(4L)
    static final Pointer FAKE_RESULT_HANDLE = new Pointer(5L)
    static final byte[] KEY_PACKAGE = [0x0A, 0x0B, 0x0C] as byte[]

    LibDave lib = Mock()
    DaveSessionManager.VoiceGatewaySender sender = Mock()
    long channelId = CHANNEL_ID
    DaveSessionManager manager

    def setup() {
        lib.daveSessionCreate(_, _, _, _) >> FAKE_SESSION_HANDLE
        lib.daveEncryptorCreate() >> FAKE_ENCRYPTOR_HANDLE
        lib.daveDecryptorCreate() >> FAKE_DECRYPTOR_HANDLE
        lib.daveSessionGetMarshalledKeyPackage(FAKE_SESSION_HANDLE, _ as PointerByReference, _ as LongByReference) >> { args ->
            Memory memory = new Memory(KEY_PACKAGE.length)
            memory.write(0, KEY_PACKAGE, 0, KEY_PACKAGE.length)
            args[1].setValue(memory)
            args[2].setValue((long) KEY_PACKAGE.length)
        }
        lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET
        manager = new DaveSessionManager({ channelId } as LongSupplier, SELF_USER_ID, sender, lib)
    }

    // ---- helpers ----

    static byte[] payload(int transitionId, int... data) {
        byte[] result = new byte[data.length + 2]
        result[0] = (byte) (transitionId >> 8)
        result[1] = (byte) transitionId
        data.eachWithIndex { b, i -> result[i + 2] = (byte) b }
        return result
    }

    static String transitionMessage(int op, int transitionId) {
        "{\"op\":${op},\"d\":{\"transition_id\":${transitionId}}}"
    }

    static byte[] keyPackageFrame() {
        ([26] + (KEY_PACKAGE as List)) as byte[]
    }

    void welcomeSucceeds() {
        lib.daveSessionProcessWelcome(*_) >> FAKE_RESULT_HANDLE
    }

    void welcomeFails() {
        lib.daveSessionProcessWelcome(*_) >> null
    }

    void commitReturns(boolean failed, boolean ignored) {
        lib.daveSessionProcessCommit(*_) >> FAKE_RESULT_HANDLE
        lib.daveCommitResultIsFailed(FAKE_RESULT_HANDLE) >> (byte) (failed ? 1 : 0)
        lib.daveCommitResultIsIgnored(FAKE_RESULT_HANDLE) >> (byte) (ignored ? 1 : 0)
    }

    void establishViaWelcome() {
        welcomeSucceeds()
        manager.initialize(PROTOCOL_VERSION, SSRC)
        manager.handleWelcome(payload(0, 0x01))
        assert manager.state == DaveSessionManager.State.ESTABLISHED
    }

    // ---- initialization ----

    def 'initial state is INACTIVE'() {
        expect:
            manager.state == DaveSessionManager.State.INACTIVE
            !manager.isDaveActive()
    }

    def 'initialize with null lib stays INACTIVE'() {
        given:
            def managerWithoutLib = new DaveSessionManager({ CHANNEL_ID } as LongSupplier, SELF_USER_ID, sender, null)

        when:
            managerWithoutLib.initialize(PROTOCOL_VERSION, SSRC)

        then:
            managerWithoutLib.state == DaveSessionManager.State.INACTIVE
            0 * sender._
    }

    def 'initialize uses the voice channel as MLS group and sends a key package right away'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            1 * lib.daveSessionInit(FAKE_SESSION_HANDLE, NATIVE_PROTOCOL_VERSION, CHANNEL_ID, SELF_USER_ID)
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_ON)
            1 * lib.daveEncryptorAssignSsrcToCodec(FAKE_ENCRYPTOR_HANDLE, SSRC, 1)
            1 * lib.daveDecryptorTransitionToPassthroughMode(FAKE_DECRYPTOR_HANDLE, PT_ON)
            1 * sender.sendBinaryFrame(keyPackageFrame())
            manager.state == DaveSessionManager.State.AWAITING_GROUP
            manager.isDaveActive()
    }

    def 'external sender is handed to libdave without sending a second key package'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] externalSender = [0x01, 0x02] as byte[]

        when:
            manager.handleExternalSender(externalSender)

        then:
            1 * lib.daveSessionSetExternalSender(FAKE_SESSION_HANDLE, externalSender, 2L)
            0 * sender.sendBinaryFrame(_)
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'external sender is not replaced once in a group'() {
        given:
            establishViaWelcome()

        when:
            manager.handleExternalSender([0x01] as byte[])

        then:
            0 * lib.daveSessionSetExternalSender(*_)
    }

    def 'messages before initialize are ignored without native calls'() {
        when:
            manager.handleExternalSender([0x01] as byte[])
            manager.handleProposals([0x01] as byte[])
            manager.handleCommitTransition(payload(1, 0x01))
            manager.handleWelcome(payload(1, 0x01))
            manager.handlePrepareTransition(1, 1)
            manager.handleExecuteTransition(1)
            manager.handlePrepareEpoch(1, 1)

        then:
            0 * lib.daveSessionSetExternalSender(*_)
            0 * lib.daveSessionProcessProposals(*_)
            0 * lib.daveSessionProcessCommit(*_)
            0 * lib.daveSessionProcessWelcome(*_)
            0 * sender._
            manager.state == DaveSessionManager.State.INACTIVE
    }

    // ---- joining: welcome ----

    def 'welcome with transition 0 executes immediately without transition ready'() {
        given:
            welcomeSucceeds()
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleWelcome(payload(0, 0x01, 0x02))

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_ENCRYPTOR_HANDLE, FAKE_KEY_RATCHET)
            1 * lib.daveKeyRatchetDestroy(FAKE_KEY_RATCHET)
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_OFF)
            1 * lib.daveDecryptorTransitionToPassthroughMode(FAKE_DECRYPTOR_HANDLE, PT_OFF)
            1 * lib.daveWelcomeResultDestroy(FAKE_RESULT_HANDLE)
            0 * sender.sendTextFrame(_)
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'welcome with a non-zero transition waits for execute before switching the sender key'() {
        given:
            welcomeSucceeds()
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleWelcome(payload(5, 0x01))

        then:
            1 * sender.sendTextFrame(transitionMessage(23, 5))
            0 * lib.daveEncryptorSetKeyRatchet(*_)
            0 * lib.daveEncryptorSetPassthroughMode(_, PT_OFF)
            manager.state == DaveSessionManager.State.TRANSITIONING

        when:
            manager.handleExecuteTransition(5)

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_ENCRYPTOR_HANDLE, FAKE_KEY_RATCHET)
            1 * lib.daveKeyRatchetDestroy(FAKE_KEY_RATCHET)
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_OFF)
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'transition ID is read as big-endian uint16'() {
        given:
            welcomeSucceeds()
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleWelcome([0x01, 0x00, 0x7F] as byte[])

        then:
            1 * sender.sendTextFrame(transitionMessage(23, 256))
            1 * lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, [0x7F] as byte[], 1L, _ as String[], _) >> FAKE_RESULT_HANDLE
    }

    def 'execute for an unknown transition is ignored'() {
        given:
            establishViaWelcome()

        when:
            manager.handleExecuteTransition(99)

        then:
            0 * lib.daveEncryptorSetKeyRatchet(*_)
            0 * lib.daveEncryptorSetPassthroughMode(*_)
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'welcome receives the recognized users including self'() {
        given:
            manager.addRecognizedUser(OTHER_USER_ID)
            manager.initialize(PROTOCOL_VERSION, SSRC)
            String[] passed = null

        when:
            manager.handleWelcome(payload(0, 0x01))

        then:
            1 * lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _, _, _ as String[], 2L) >> { args ->
                passed = args[3]
                FAKE_RESULT_HANDLE
            }
            passed as Set == [SELF_USER_ID, OTHER_USER_ID] as Set
    }

    def 'self cannot be removed from the recognized users'() {
        given:
            manager.addRecognizedUser(OTHER_USER_ID)
            manager.removeRecognizedUser(OTHER_USER_ID)
            manager.removeRecognizedUser(SELF_USER_ID)
            manager.initialize(PROTOCOL_VERSION, SSRC)
            String[] passed = null

        when:
            manager.handleProposals([0x00] as byte[])

        then:
            1 * lib.daveSessionProcessProposals(*_) >> { args -> passed = args[3] }
            passed as List == [SELF_USER_ID]
    }

    // ---- creating / updating the group: proposals and commits ----

    def 'proposals are processed while awaiting a group so the first member can create it'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            Memory commit = new Memory(2)
            commit.write(0, [0x0D, 0x0E] as byte[], 0, 2)

        when:
            manager.handleProposals([0x00, 0x09] as byte[])

        then:
            1 * lib.daveSessionProcessProposals(FAKE_SESSION_HANDLE, [0x00, 0x09] as byte[], 2L,
                    _ as String[], 1L, _ as PointerByReference, _ as LongByReference) >> { args ->
                args[5].setValue(commit)
                args[6].setValue(2L)
            }
            1 * sender.sendBinaryFrame([28, 0x0D, 0x0E] as byte[])
            1 * lib.daveFree(commit)
    }

    def 'proposals that need no commit send nothing'() {
        given:
            establishViaWelcome()

        when:
            manager.handleProposals([0x00] as byte[])

        then:
            1 * lib.daveSessionProcessProposals(*_)
            0 * sender._
    }

    def 'our own commit while awaiting a group establishes it'() {
        given:
            commitReturns(false, false)
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleCommitTransition(payload(0, 0x01))

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_ENCRYPTOR_HANDLE, FAKE_KEY_RATCHET)
            1 * lib.daveCommitResultDestroy(FAKE_RESULT_HANDLE)
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'commit with a non-zero transition sends ready and keeps the old sender key until execute'() {
        given:
            establishViaWelcome()
            commitReturns(false, false)

        when:
            manager.handleCommitTransition(payload(7, 0x01))

        then:
            1 * sender.sendTextFrame(transitionMessage(23, 7))
            0 * lib.daveEncryptorSetKeyRatchet(*_)
            manager.state == DaveSessionManager.State.TRANSITIONING

        when:
            manager.handleExecuteTransition(7)

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_ENCRYPTOR_HANDLE, FAKE_KEY_RATCHET)
            1 * lib.daveKeyRatchetDestroy(FAKE_KEY_RATCHET)
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'ignored commit is a no-op, never a reset (libdave: avoids thrashing)'() {
        given:
            establishViaWelcome()
            commitReturns(false, true)

        when:
            manager.handleCommitTransition(payload(3, 0x01))

        then:
            0 * sender._
            0 * lib.daveSessionInit(*_)
            0 * lib.daveSessionReset(*_)
            1 * lib.daveCommitResultDestroy(FAKE_RESULT_HANDLE)
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    // ---- recovery ----

    def 'failed commit sends invalid commit/welcome and starts over with a fresh key package'() {
        given:
            establishViaWelcome()
            commitReturns(true, false)

        when:
            manager.handleCommitTransition(payload(3, 0x01))

        then:
            1 * sender.sendTextFrame(transitionMessage(31, 3))
            1 * lib.daveSessionInit(FAKE_SESSION_HANDLE, NATIVE_PROTOCOL_VERSION, CHANNEL_ID, SELF_USER_ID)
            1 * sender.sendBinaryFrame(keyPackageFrame())
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_ON)
            1 * lib.daveCommitResultDestroy(FAKE_RESULT_HANDLE)
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'failed welcome sends invalid commit/welcome and keeps the recognized users'() {
        given:
            manager.addRecognizedUser(OTHER_USER_ID)
            welcomeFails()
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleWelcome(payload(4, 0x01))

        then:
            1 * sender.sendTextFrame(transitionMessage(31, 4))
            1 * sender.sendBinaryFrame(keyPackageFrame())
            manager.state == DaveSessionManager.State.AWAITING_GROUP

        when:
            manager.handleProposals([0x00] as byte[])

        then:
            1 * lib.daveSessionProcessProposals(_, _, _, _ as String[], 2L, _, _)
    }

    def 'a burst of failures during recovery sends only one invalid commit/welcome'() {
        given:
            welcomeFails()
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleWelcome(payload(4, 0x01))
            manager.handleWelcome(payload(5, 0x01))

        then:
            1 * sender.sendTextFrame(transitionMessage(31, 4))
            0 * sender.sendTextFrame(transitionMessage(31, 5))
    }

    def 'recovery completes with a new welcome, after which failures are reported again'() {
        given:
            int welcomes = 0
            lib.daveSessionProcessWelcome(*_) >> { welcomes++ == 0 ? null : FAKE_RESULT_HANDLE }
            commitReturns(true, false)
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleWelcome(payload(4, 0x01))
            manager.handleWelcome(payload(0, 0x01))

        then:
            manager.state == DaveSessionManager.State.ESTABLISHED

        when:
            manager.handleCommitTransition(payload(6, 0x01))

        then:
            1 * sender.sendTextFrame(transitionMessage(31, 6))
    }

    // ---- epochs ----

    def 'prepare epoch 1 re-initializes the session (not a bare reset) and sends a new key package'() {
        given:
            establishViaWelcome()

        when:
            manager.handlePrepareEpoch(1, PROTOCOL_VERSION)

        then:
            1 * lib.daveSessionInit(FAKE_SESSION_HANDLE, NATIVE_PROTOCOL_VERSION, CHANNEL_ID, SELF_USER_ID)
            0 * lib.daveSessionReset(*_)
            1 * sender.sendBinaryFrame(keyPackageFrame())
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_ON)
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'prepare epoch 1 drops pending transitions'() {
        given:
            establishViaWelcome()
            commitReturns(false, false)
            manager.handleCommitTransition(payload(8, 0x01))

        when:
            manager.handlePrepareEpoch(1, PROTOCOL_VERSION)
            manager.handleExecuteTransition(8)

        then:
            0 * lib.daveEncryptorSetKeyRatchet(*_)
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'prepare epoch greater than 1 keeps the group'() {
        given:
            establishViaWelcome()

        when:
            manager.handlePrepareEpoch(2, PROTOCOL_VERSION)

        then:
            0 * lib.daveSessionInit(*_)
            0 * sender._
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 're-initialization reads the current channel as group ID'() {
        given:
            establishViaWelcome()
            channelId = 42L

        when:
            manager.handlePrepareEpoch(1, PROTOCOL_VERSION)

        then:
            1 * lib.daveSessionInit(FAKE_SESSION_HANDLE, NATIVE_PROTOCOL_VERSION, 42L, SELF_USER_ID)
    }

    // ---- protocol version transitions ----

    def 'downgrade: receive side passes through at prepare, send side at execute'() {
        given:
            establishViaWelcome()

        when:
            manager.handlePrepareTransition(0, 7)

        then:
            1 * lib.daveDecryptorTransitionToPassthroughMode(FAKE_DECRYPTOR_HANDLE, PT_ON)
            1 * sender.sendTextFrame(transitionMessage(23, 7))
            0 * lib.daveEncryptorSetPassthroughMode(*_)
            manager.state == DaveSessionManager.State.ESTABLISHED

        when:
            manager.handleExecuteTransition(7)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_ON)
            0 * lib.daveEncryptorSetPassthroughMode(_, PT_OFF)
            manager.state == DaveSessionManager.State.PASSTHROUGH
            !manager.isDaveActive()
    }

    def 'prepare transition 0 executes immediately without transition ready'() {
        given:
            establishViaWelcome()

        when:
            manager.handlePrepareTransition(0, 0)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_ON)
            0 * sender.sendTextFrame(_)
            manager.state == DaveSessionManager.State.PASSTHROUGH
    }

    def 'upgrade after a downgrade starts a new group via epoch 1'() {
        given:
            establishViaWelcome()
            manager.handlePrepareTransition(0, 0)

        when:
            manager.handlePrepareEpoch(1, PROTOCOL_VERSION)

        then:
            1 * lib.daveSessionInit(FAKE_SESSION_HANDLE, NATIVE_PROTOCOL_VERSION, CHANNEL_ID, SELF_USER_ID)
            1 * sender.sendBinaryFrame(keyPackageFrame())
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'executing a transition without an MLS group keeps passing through'() {
        given:
            LibDave noGroupLib = Mock()
            noGroupLib.daveSessionCreate(*_) >> FAKE_SESSION_HANDLE
            noGroupLib.daveEncryptorCreate() >> FAKE_ENCRYPTOR_HANDLE
            noGroupLib.daveDecryptorCreate() >> FAKE_DECRYPTOR_HANDLE
            noGroupLib.daveSessionGetKeyRatchet(*_) >> null
            def noGroupManager = new DaveSessionManager({ CHANNEL_ID } as LongSupplier, SELF_USER_ID, sender, noGroupLib)
            noGroupManager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            noGroupManager.handlePrepareTransition(PROTOCOL_VERSION, 0)

        then:
            0 * noGroupLib.daveEncryptorSetPassthroughMode(_, PT_OFF)
            0 * noGroupLib.daveKeyRatchetDestroy(*_)
            noGroupManager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    // ---- teardown ----

    def 'close destroys all native handles and later messages are ignored'() {
        given:
            establishViaWelcome()

        when:
            manager.close()

        then:
            1 * lib.daveEncryptorDestroy(FAKE_ENCRYPTOR_HANDLE)
            1 * lib.daveDecryptorDestroy(FAKE_DECRYPTOR_HANDLE)
            1 * lib.daveSessionDestroy(FAKE_SESSION_HANDLE)
            manager.state == DaveSessionManager.State.INACTIVE
            manager.encryptor == null

        when:
            manager.handleWelcome(payload(0, 0x01))
            manager.handleExecuteTransition(1)

        then:
            0 * lib._
            0 * sender._
    }

    def 're-initializing destroys the previous native handles first'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            1 * lib.daveEncryptorDestroy(FAKE_ENCRYPTOR_HANDLE)
            1 * lib.daveDecryptorDestroy(FAKE_DECRYPTOR_HANDLE)
            1 * lib.daveSessionDestroy(FAKE_SESSION_HANDLE)
    }

    def 'isEncryptionActive reflects an established sender key outside passthrough'() {
        given:
            lib.daveEncryptorHasKeyRatchet(FAKE_ENCRYPTOR_HANDLE) >> (byte) 1
            lib.daveEncryptorIsPassthroughMode(FAKE_ENCRYPTOR_HANDLE) >> (byte) 0

        expect:
            !manager.isEncryptionActive()

        when:
            establishViaWelcome()

        then:
            manager.isEncryptionActive()
    }

    // ---- paths added after review ----

    def 'prepare transition to a non-zero version with a non-zero ID switches keys at execute'() {
        given:
            establishViaWelcome()

        when:
            manager.handlePrepareTransition(PROTOCOL_VERSION, 9)

        then:
            1 * sender.sendTextFrame(transitionMessage(23, 9))
            0 * lib.daveEncryptorSetKeyRatchet(*_)

        when:
            manager.handleExecuteTransition(9)

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_ENCRYPTOR_HANDLE, FAKE_KEY_RATCHET)
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'prepare epoch 1 with version 0 resets to passthrough without a key package'() {
        given:
            establishViaWelcome()

        when:
            manager.handlePrepareEpoch(1, 0)

        then:
            1 * lib.daveSessionReset(FAKE_SESSION_HANDLE)
            0 * lib.daveSessionInit(*_)
            0 * sender.sendBinaryFrame(_)
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, PT_ON)
            manager.state == DaveSessionManager.State.PASSTHROUGH
    }

    def 'initialize with version 0 passes through and upgrades via epoch 1'() {
        when:
            manager.initialize(0, SSRC)

        then:
            0 * sender._
            manager.state == DaveSessionManager.State.PASSTHROUGH

        when:
            manager.handleExternalSender([0x01] as byte[])
            manager.handlePrepareEpoch(1, PROTOCOL_VERSION)

        then:
            1 * lib.daveSessionSetExternalSender(FAKE_SESSION_HANDLE, _, 1L)
            1 * lib.daveSessionInit(FAKE_SESSION_HANDLE, NATIVE_PROTOCOL_VERSION, CHANNEL_ID, SELF_USER_ID)
            1 * sender.sendBinaryFrame(keyPackageFrame())
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'a key package libdave cannot produce is not sent'() {
        given:
            LibDave noKeyLib = Mock()
            noKeyLib.daveSessionCreate(*_) >> FAKE_SESSION_HANDLE
            noKeyLib.daveEncryptorCreate() >> FAKE_ENCRYPTOR_HANDLE
            noKeyLib.daveDecryptorCreate() >> FAKE_DECRYPTOR_HANDLE
            def noKeyManager = new DaveSessionManager({ CHANNEL_ID } as LongSupplier, SELF_USER_ID, sender, noKeyLib)

        when:
            noKeyManager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            0 * sender.sendBinaryFrame(_)
            noKeyManager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'a departed member is accepted in a welcome but not for add proposals'() {
        given:
            manager.addRecognizedUser(OTHER_USER_ID)
            manager.removeRecognizedUser(OTHER_USER_ID)
            manager.initialize(PROTOCOL_VERSION, SSRC)
            String[] forWelcome = null
            String[] forProposals = null

        when:
            manager.handleWelcome(payload(0, 0x01))
            manager.handleProposals([0x00] as byte[])

        then:
            1 * lib.daveSessionProcessWelcome(*_) >> { args ->
                forWelcome = args[3]
                FAKE_RESULT_HANDLE
            }
            1 * lib.daveSessionProcessProposals(*_) >> { args -> forProposals = args[3] }
            forWelcome as Set == [SELF_USER_ID, OTHER_USER_ID] as Set
            forProposals as List == [SELF_USER_ID]
    }

    def 'a member who reconnects is recognized for add proposals again'() {
        given:
            manager.addRecognizedUser(OTHER_USER_ID)
            manager.removeRecognizedUser(OTHER_USER_ID)
            manager.addRecognizedUser(OTHER_USER_ID)
            establishViaWelcome()
            String[] forProposals = null

        when:
            manager.handleProposals([0x00] as byte[])

        then:
            1 * lib.daveSessionProcessProposals(*_) >> { args -> forProposals = args[3] }
            forProposals as Set == [SELF_USER_ID, OTHER_USER_ID] as Set
    }

    def 'isEncryptionActive needs the established state, a key ratchet and no passthrough'() {
        given:
            byte hasKey = 1
            byte passthrough = 0
            lib.daveEncryptorHasKeyRatchet(FAKE_ENCRYPTOR_HANDLE) >> { hasKey }
            lib.daveEncryptorIsPassthroughMode(FAKE_ENCRYPTOR_HANDLE) >> { passthrough }
            welcomeSucceeds()
            manager.initialize(PROTOCOL_VERSION, SSRC)

        expect: 'not established yet'
            !manager.isEncryptionActive()

        when:
            manager.handleWelcome(payload(0, 0x01))

        then:
            manager.isEncryptionActive()

        when:
            hasKey = 0

        then:
            !manager.isEncryptionActive()

        when:
            hasKey = 1
            passthrough = 1

        then:
            !manager.isEncryptionActive()
    }
}
