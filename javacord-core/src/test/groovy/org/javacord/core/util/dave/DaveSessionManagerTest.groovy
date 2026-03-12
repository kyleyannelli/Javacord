package org.javacord.core.util.dave

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import spock.lang.Specification
import spock.lang.Subject

@Subject(DaveSessionManager)
class DaveSessionManagerTest extends Specification {

    static final long GUILD_ID = 123456789L
    static final String SELF_USER_ID = '987654321'
    static final int SSRC = 12345
    static final int PROTOCOL_VERSION = 1
    static final Pointer FAKE_SESSION_HANDLE = new Pointer(1L)
    static final Pointer FAKE_ENCRYPTOR_HANDLE = new Pointer(2L)
    static final Pointer FAKE_DECRYPTOR_HANDLE = new Pointer(3L)
    static final Pointer FAKE_KEY_RATCHET = new Pointer(4L)

    LibDave lib = Mock()
    DaveSessionManager.VoiceGatewaySender sender = Mock()

    DaveSessionManager manager

    def setup() {
        lib.daveSessionCreate(_, _, _, _) >> FAKE_SESSION_HANDLE
        lib.daveEncryptorCreate() >> FAKE_ENCRYPTOR_HANDLE
        lib.daveDecryptorCreate() >> FAKE_DECRYPTOR_HANDLE
        manager = new DaveSessionManager(GUILD_ID, SELF_USER_ID, sender, lib)
    }

    def 'initial state is INACTIVE'() {
        expect:
            manager.state == DaveSessionManager.State.INACTIVE
    }

    def 'initialize transitions state to PENDING'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            manager.state == DaveSessionManager.State.PENDING
    }

    def 'initialize with null lib stays INACTIVE'() {
        given:
            def managerWithoutLib = new DaveSessionManager(GUILD_ID, SELF_USER_ID, sender, null)

        when:
            managerWithoutLib.initialize(PROTOCOL_VERSION, SSRC)

        then:
            managerWithoutLib.state == DaveSessionManager.State.INACTIVE
    }

    def 'initialize creates encryptor in passthrough mode'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, true)
    }

    def 'initialize assigns SSRC to opus codec on encryptor'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            1 * lib.daveEncryptorAssignSsrcToCodec(FAKE_ENCRYPTOR_HANDLE, SSRC, 1)
    }

    def 'initialize creates decryptor in passthrough mode'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            1 * lib.daveDecryptorTransitionToPassthroughMode(FAKE_DECRYPTOR_HANDLE, true)
    }

    def 'initialize inits native session with correct parameters'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            1 * lib.daveSessionInit(FAKE_SESSION_HANDLE, { it == (short) PROTOCOL_VERSION }, GUILD_ID, SELF_USER_ID)
    }

    def 'handleExternalSender sets external sender and transitions to AWAITING_GROUP'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] externalSenderData = [0x01, 0x02, 0x03] as byte[]

        when:
            manager.handleExternalSender(externalSenderData)

        then:
            1 * lib.daveSessionSetExternalSender(FAKE_SESSION_HANDLE, externalSenderData, 3)
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'handleExternalSender requests key package from session'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleExternalSender([0x01] as byte[])

        then:
            1 * lib.daveSessionGetMarshalledKeyPackage(FAKE_SESSION_HANDLE, _ as PointerByReference, _ as IntByReference)
    }

    def 'handleExternalSender in INACTIVE state is a no-op'() {
        when:
            manager.handleExternalSender([0x01] as byte[])

        then:
            0 * lib.daveSessionSetExternalSender(_, _, _)
            manager.state == DaveSessionManager.State.INACTIVE
    }

    def 'handleProposals invokes processProposals on native session'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] proposalsData = [0x01, 0x02] as byte[]

        when:
            manager.handleProposals(proposalsData)

        then:
            1 * lib.daveSessionProcessProposals(FAKE_SESSION_HANDLE, proposalsData, 2,
                    _ as String[], _, _ as PointerByReference, _ as IntByReference)
    }

    def 'handleCommitTransition parses transition ID from first two payload bytes'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x05, 0x01, 0x02, 0x03] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], 3) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> false
            lib.daveCommitResultIsIgnored(commitResultHandle) >> false
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleCommitTransition(payload)

        then:
            1 * sender.sendTextFrame({ String json ->
                json.contains('"transition_id":5')
            })
    }

    def 'handleCommitTransition with big-endian transition ID 0x0100 parses as 256'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x01, 0x00, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], 1) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> false
            lib.daveCommitResultIsIgnored(commitResultHandle) >> false
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleCommitTransition(payload)

        then:
            1 * sender.sendTextFrame({ String json ->
                json.contains('"transition_id":256')
            })
    }

    def 'handleCommitTransition on success transitions to TRANSITIONING and sends transition ready'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x0A, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> false
            lib.daveCommitResultIsIgnored(commitResultHandle) >> false
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleCommitTransition(payload)

        then:
            manager.state == DaveSessionManager.State.TRANSITIONING
            1 * sender.sendTextFrame({ String json ->
                json.contains('"op":23') && json.contains('"transition_id":10')
            })
    }

    def 'handleCommitTransition on failed commit sends invalid commit and resets'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x07, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> true

        when:
            manager.handleCommitTransition(payload)

        then:
            1 * sender.sendTextFrame({ String json ->
                json.contains('"op":31') && json.contains('"transition_id":7')
            })
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'handleCommitTransition on ignored commit does not transition or send messages'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x01, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> false
            lib.daveCommitResultIsIgnored(commitResultHandle) >> true

        when:
            manager.handleCommitTransition(payload)

        then:
            0 * sender.sendTextFrame(_)
            0 * sender.sendBinaryFrame(_)
    }

    def 'handleCommitTransition with too-short payload is a no-op'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleCommitTransition([0x01] as byte[])

        then:
            0 * lib.daveSessionProcessCommit(_, _, _)
    }

    def 'handleWelcome on success transitions to TRANSITIONING and sends transition ready'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x03, 0x01] as byte[]
            Pointer welcomeResultHandle = new Pointer(11L)
            lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _, _ as String[], _) >> welcomeResultHandle
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleWelcome(payload)

        then:
            manager.state == DaveSessionManager.State.TRANSITIONING
            1 * sender.sendTextFrame({ String json ->
                json.contains('"op":23') && json.contains('"transition_id":3')
            })
    }

    def 'handleWelcome on failure sends invalid commit and resets to AWAITING_GROUP'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x04, 0x01] as byte[]
            lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _, _ as String[], _) >> null

        when:
            manager.handleWelcome(payload)

        then:
            1 * sender.sendTextFrame({ String json ->
                json.contains('"op":31') && json.contains('"transition_id":4')
            })
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'handleWelcome with too-short payload is a no-op'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleWelcome([0x01] as byte[])

        then:
            0 * lib.daveSessionProcessWelcome(_, _, _, _, _)
    }

    def 'handlePrepareTransition with version 0 enables passthrough on encryptor and decryptor'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handlePrepareTransition(0, 1)

        then:
            (1.._) * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, true)
            (1.._) * lib.daveDecryptorTransitionToPassthroughMode(FAKE_DECRYPTOR_HANDLE, true)
    }

    def 'handlePrepareTransition with INIT_TRANSITION_ID executes transition immediately'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handlePrepareTransition(1, 0)

        then:
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'handlePrepareTransition with non-zero transition sends ready via text frame'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handlePrepareTransition(1, 5)

        then:
            1 * sender.sendTextFrame({ String json ->
                json.contains('"op":23') && json.contains('"transition_id":5')
            })
    }

    def 'handleExecuteTransition transitions to ESTABLISHED'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleExecuteTransition(5)

        then:
            manager.state == DaveSessionManager.State.ESTABLISHED
    }

    def 'handleExecuteTransition disables passthrough on encryptor when protocol version is positive'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handleExecuteTransition(5)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, false)
    }

    def 'handlePrepareEpoch with epoch 1 resets session and resends key package'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handlePrepareEpoch(1, PROTOCOL_VERSION)

        then:
            1 * lib.daveSessionReset(FAKE_SESSION_HANDLE)
            1 * lib.daveSessionSetProtocolVersion(FAKE_SESSION_HANDLE, { it == (short) PROTOCOL_VERSION })

        and:
            manager.state == DaveSessionManager.State.AWAITING_GROUP
    }

    def 'handlePrepareEpoch with epoch greater than 1 does not reset session'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.handlePrepareEpoch(2, PROTOCOL_VERSION)

        then:
            0 * lib.daveSessionReset(_)
    }

    def 'addRecognizedUser and removeRecognizedUser track users correctly'() {
        when:
            manager.addRecognizedUser('111')
            manager.addRecognizedUser('222')
            manager.addRecognizedUser('333')

        then:
            manager.@recognizedUserIds.size() == 3

        when:
            manager.removeRecognizedUser('222')

        then:
            manager.@recognizedUserIds.size() == 2
            !manager.@recognizedUserIds.contains('222')
    }

    def 'isDaveActive returns false when INACTIVE'() {
        expect:
            !manager.isDaveActive()
    }

    def 'isDaveActive returns true after initialization'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        expect:
            manager.isDaveActive()
    }

    def 'close destroys all native handles and transitions to INACTIVE'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        when:
            manager.close()

        then:
            1 * lib.daveEncryptorDestroy(FAKE_ENCRYPTOR_HANDLE)
            1 * lib.daveDecryptorDestroy(FAKE_DECRYPTOR_HANDLE)
            1 * lib.daveSessionDestroy(FAKE_SESSION_HANDLE)

        and:
            manager.state == DaveSessionManager.State.INACTIVE
            manager.encryptor == null
            manager.decryptor == null
    }

    def 'close clears recognized user IDs'() {
        given:
            manager.addRecognizedUser('111')
            manager.addRecognizedUser('222')

        when:
            manager.close()

        then:
            manager.@recognizedUserIds.isEmpty()
    }

    def 'getEncryptor returns non-null after initialize'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            manager.encryptor != null
    }

    def 'getDecryptor returns non-null after initialize'() {
        when:
            manager.initialize(PROTOCOL_VERSION, SSRC)

        then:
            manager.decryptor != null
    }

    // --- Group A: Recognized users forwarded to native calls ---

    def 'handleWelcome passes recognized users to native session'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            manager.addRecognizedUser('111')
            manager.addRecognizedUser('222')
            manager.addRecognizedUser('333')
            byte[] payload = [0x00, 0x01, 0x01] as byte[]

        when:
            manager.handleWelcome(payload)

        then:
            1 * lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _,
                    { String[] ids -> ids.toList().toSet() == ['111', '222', '333'].toSet() },
                    3) >> null
    }

    def 'handleProposals passes recognized users to native session'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            manager.addRecognizedUser('AAA')
            manager.addRecognizedUser('BBB')
            byte[] proposalsData = [0x01, 0x02] as byte[]

        when:
            manager.handleProposals(proposalsData)

        then:
            1 * lib.daveSessionProcessProposals(FAKE_SESSION_HANDLE, proposalsData, 2,
                    { String[] ids -> ids.toList().toSet() == ['AAA', 'BBB'].toSet() }, 2,
                    _ as PointerByReference, _ as IntByReference)
    }

    // --- Group B: Passthrough mode during recovery ---

    def 'failed commit recovery puts encryptor in passthrough mode'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x07, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> true

        when:
            manager.handleCommitTransition(payload)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, true)
    }

    def 'failed welcome recovery puts encryptor in passthrough mode'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x04, 0x01] as byte[]
            lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _, _ as String[], _) >> null

        when:
            manager.handleWelcome(payload)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, true)
    }

    // --- Group C: Recovery preserves state ---

    def 'recovery after failed commit preserves recognized users'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            manager.addRecognizedUser('111')
            manager.addRecognizedUser('222')
            byte[] payload = [0x00, 0x07, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> true

        when:
            manager.handleCommitTransition(payload)

        then:
            manager.@recognizedUserIds.containsAll(['111', '222'])
            manager.@recognizedUserIds.size() == 2
    }

    def 'recovery after failed welcome preserves recognized users'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            manager.addRecognizedUser('111')
            manager.addRecognizedUser('222')
            byte[] payload = [0x00, 0x04, 0x01] as byte[]
            lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _, _ as String[], _) >> null

        when:
            manager.handleWelcome(payload)

        then:
            manager.@recognizedUserIds.containsAll(['111', '222'])
            manager.@recognizedUserIds.size() == 2
    }

    // --- Group D: Key ratchet management ---

    def 'successful commit sets key ratchet on encryptor'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x05, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> false
            lib.daveCommitResultIsIgnored(commitResultHandle) >> false
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleCommitTransition(payload)

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_ENCRYPTOR_HANDLE, FAKE_KEY_RATCHET)
    }

    def 'successful welcome sets key ratchet on encryptor'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x01, 0x01] as byte[]
            Pointer welcomeResultHandle = new Pointer(11L)
            lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _, _ as String[], _) >> welcomeResultHandle
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleWelcome(payload)

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_ENCRYPTOR_HANDLE, FAKE_KEY_RATCHET)
    }

    // --- Group E: Native resource cleanup ---

    def 'commit result handle is destroyed after successful processing'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x05, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> false
            lib.daveCommitResultIsIgnored(commitResultHandle) >> false
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleCommitTransition(payload)

        then:
            1 * lib.daveCommitResultDestroy(commitResultHandle)
    }

    def 'commit result handle is destroyed after failed processing'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x07, 0x01] as byte[]
            Pointer commitResultHandle = new Pointer(10L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> commitResultHandle
            lib.daveCommitResultIsFailed(commitResultHandle) >> true

        when:
            manager.handleCommitTransition(payload)

        then:
            1 * lib.daveCommitResultDestroy(commitResultHandle)
    }

    def 'welcome result handle is destroyed after successful processing'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            byte[] payload = [0x00, 0x01, 0x01] as byte[]
            Pointer welcomeResultHandle = new Pointer(11L)
            lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _, _ as String[], _) >> welcomeResultHandle
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when:
            manager.handleWelcome(payload)

        then:
            1 * lib.daveWelcomeResultDestroy(welcomeResultHandle)
    }

    // --- Group F: Full lifecycle ---

    def 'full recovery: failed commit then new welcome re-establishes session'() {
        given:
            manager.initialize(PROTOCOL_VERSION, SSRC)
            manager.addRecognizedUser('111')
            Pointer failedCommitResult = new Pointer(10L)
            Pointer newWelcomeResult = new Pointer(12L)
            lib.daveSessionProcessCommit(FAKE_SESSION_HANDLE, _ as byte[], _) >> failedCommitResult
            lib.daveCommitResultIsFailed(failedCommitResult) >> true
            lib.daveSessionProcessWelcome(FAKE_SESSION_HANDLE, _ as byte[], _, _ as String[], _) >> newWelcomeResult
            lib.daveSessionGetKeyRatchet(FAKE_SESSION_HANDLE, SELF_USER_ID) >> FAKE_KEY_RATCHET

        when: 'commit fails'
            manager.handleCommitTransition([0x00, 0x07, 0x01] as byte[])

        then: 'encryptor goes to passthrough and state resets to AWAITING_GROUP'
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, true)
            manager.state == DaveSessionManager.State.AWAITING_GROUP

        when: 'new welcome arrives and succeeds'
            manager.handleWelcome([0x00, 0x08, 0x01] as byte[])

        then: 'state transitions to TRANSITIONING'
            manager.state == DaveSessionManager.State.TRANSITIONING

        when: 'execute transition'
            manager.handleExecuteTransition(8)

        then: 'session is fully re-established with encryption active'
            manager.state == DaveSessionManager.State.ESTABLISHED
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_ENCRYPTOR_HANDLE, false)
    }
}
