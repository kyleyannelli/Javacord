package org.javacord.core.util.dave

import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

import java.util.function.LongSupplier

/**
 * Runs against a real libdave build to verify the JNA bindings (size_t widths, bool returns,
 * buffer ownership) and libdave's commit/welcome semantics the session manager relies on.
 *
 * <p>Skipped unless {@code LIBDAVE_PATH} points at the directory containing the libdave shared library,
 * e.g. the {@code lib} directory of a release from https://github.com/discord/libdave/releases.
 */
@Requires({ System.getenv('LIBDAVE_PATH') })
class LibDaveNativeIntegrationTest extends Specification {

    static final long CHANNEL_ID = 1184647280066822234L
    static final String SELF_USER_ID = '806350925723205642'

    @Shared
    LibDave lib

    def setupSpec() {
        System.setProperty('jna.library.path', System.getenv('LIBDAVE_PATH'))
        // other specs may have recorded a failed load in this JVM
        def attempted = LibDaveLoader.getDeclaredField('loadAttempted')
        attempted.accessible = true
        attempted.set(null, false)
        lib = LibDaveLoader.getInstance()
    }

    def 'libdave loads and supports DAVE protocol version 1'() {
        expect:
            lib != null
            LibDaveLoader.isAvailable()
            lib.daveMaxSupportedProtocolVersion() >= 1
    }

    def 'a key package is produced through the size_t out-parameters and freed'() {
        given:
            def session = new DaveSession(lib)
            session.init(1, CHANNEL_ID, SELF_USER_ID)

        when:
            def first = session.getMarshalledKeyPackage()
            def second = session.getMarshalledKeyPackage()

        then: 'a real MLS KeyPackage is well over 100 bytes; each call makes a new one'
            first.get().length > 100
            second.get().length > 100
            first.get() != second.get()

        cleanup:
            session.close()
    }

    def 'a bare reset leaves libdave unable to make a key package, init restores it'() {
        given:
            def session = new DaveSession(lib)
            session.init(1, CHANNEL_ID, SELF_USER_ID)

        when:
            session.reset()

        then: 'why the epoch-1 path must call init, not reset'
            !session.getMarshalledKeyPackage().isPresent()

        when:
            session.init(1, CHANNEL_ID, SELF_USER_ID)

        then:
            session.getMarshalledKeyPackage().isPresent()

        cleanup:
            session.close()
    }

    def 'bool returns read correctly: a commit without any MLS state is ignored, not failed'() {
        given:
            def session = new DaveSession(lib)
            session.init(1, CHANNEL_ID, SELF_USER_ID)

        when:
            def result = session.processCommit([0x00, 0x01, 0x02, 0x03] as byte[])

        then:
            result.isIgnored()
            !result.isFailed()

        cleanup:
            result?.close()
            session.close()
    }

    def 'a garbage welcome fails'() {
        given:
            def session = new DaveSession(lib)
            session.init(1, CHANNEL_ID, SELF_USER_ID)
            session.getMarshalledKeyPackage()

        when:
            def result = session.processWelcome([0x00, 0x01, 0x02] as byte[], [SELF_USER_ID] as Set)

        then:
            result.isFailed()

        cleanup:
            result?.close()
            session.close()
    }

    def 'encryptor passes frames through, reports state correctly, and refuses to send without a key'() {
        given:
            def encryptor = new DaveFrameEncryptor(lib)
            encryptor.assignSsrcToOpus(1234)
            byte[] frame = (1..120).collect { (byte) it } as byte[]

        when:
            encryptor.setPassthroughMode(true)

        then:
            encryptor.isPassthroughMode()
            !encryptor.hasKeyRatchet()
            encryptor.encrypt(1234, frame).get() == frame

        when: 'not in passthrough and no key ratchet: libdave refuses, so the frame must be dropped'
            encryptor.setPassthroughMode(false)

        then:
            !encryptor.isPassthroughMode()
            !encryptor.encrypt(1234, frame).isPresent()

        cleanup:
            encryptor.close()
    }

    def 'session manager against real libdave: ignored commit is a no-op, failed welcome recovers once'() {
        given:
            List<byte[]> binary = []
            List<String> text = []
            def sender = new DaveSessionManager.VoiceGatewaySender() {
                void sendBinaryFrame(byte[] f) { binary << f }
                void sendTextFrame(String t) { text << t }
            }
            def manager = new DaveSessionManager({ CHANNEL_ID } as LongSupplier, SELF_USER_ID, sender, lib)

        when:
            manager.initialize(1, 1234)

        then: 'op 26 with a real key package'
            binary.size() == 1
            binary[0][0] == 26 as byte
            binary[0].length > 100
            manager.state == DaveSessionManager.State.AWAITING_GROUP

        when:
            manager.handleCommitTransition([0x00, 0x03, 0x00, 0x01] as byte[])

        then:
            text.isEmpty()
            binary.size() == 1
            manager.state == DaveSessionManager.State.AWAITING_GROUP

        when:
            manager.handleWelcome([0x00, 0x04, 0x00, 0x01, 0x02] as byte[])

        then: 'op 31 for transition 4, then a fresh key package'
            text == ['{"op":31,"d":{"transition_id":4}}']
            binary.size() == 2
            binary[1][0] == 26 as byte
            binary[1] != binary[0]

        cleanup:
            manager.close()
    }

    def 'C bool parameters arrive as a valid bool (JNA would pass a Java true as -1)'() {
        given:
            def handle = lib.daveEncryptorCreate()

        when:
            lib.daveEncryptorSetPassthroughMode(handle, (byte) 1)

        then: 'libdave stores and returns exactly 1, not 0xFF'
            lib.daveEncryptorIsPassthroughMode(handle) == (byte) 1

        when:
            lib.daveEncryptorSetPassthroughMode(handle, (byte) 0)

        then:
            lib.daveEncryptorIsPassthroughMode(handle) == (byte) 0

        cleanup:
            lib.daveEncryptorDestroy(handle)
    }
}
