package org.javacord.core.util.dave

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import spock.lang.Specification
import spock.lang.Subject

@Subject(DaveFrameEncryptor)
class DaveFrameEncryptorTest extends Specification {

    static final Pointer FAKE_HANDLE = new Pointer(1L)
    static final Pointer FAKE_KEY_RATCHET = new Pointer(2L)
    static final int SSRC = 42
    static final int MEDIA_TYPE_AUDIO = 0
    static final int CODEC_OPUS = 1

    LibDave lib = Mock()
    DaveFrameEncryptor encryptor

    def setup() {
        lib.daveEncryptorCreate() >> FAKE_HANDLE
        encryptor = new DaveFrameEncryptor(lib)
    }

    def 'encrypt returns encrypted bytes on success'() {
        given:
            byte[] frame = [0x01, 0x02, 0x03] as byte[]
            lib.daveEncryptorGetMaxCiphertextByteSize(FAKE_HANDLE, MEDIA_TYPE_AUDIO, 3) >> 32

        when:
            def result = encryptor.encrypt(SSRC, frame)

        then:
            1 * lib.daveEncryptorEncrypt(FAKE_HANDLE, MEDIA_TYPE_AUDIO, SSRC,
                    frame, 3, _ as byte[], 32, _ as IntByReference) >> { args ->
                args[7].setValue(5)
                return 0
            }
            result.isPresent()
            result.get().length == 5
    }

    def 'encrypt returns empty optional on non-zero result code'() {
        given:
            byte[] frame = [0x01] as byte[]
            lib.daveEncryptorGetMaxCiphertextByteSize(FAKE_HANDLE, MEDIA_TYPE_AUDIO, 1) >> 16

        when:
            def result = encryptor.encrypt(SSRC, frame)

        then:
            1 * lib.daveEncryptorEncrypt(FAKE_HANDLE, MEDIA_TYPE_AUDIO, SSRC,
                    frame, 1, _ as byte[], 16, _ as IntByReference) >> 1
            !result.isPresent()
    }

    def 'encrypt returns empty optional when bytes written is zero'() {
        given:
            byte[] frame = [0x01] as byte[]
            lib.daveEncryptorGetMaxCiphertextByteSize(FAKE_HANDLE, MEDIA_TYPE_AUDIO, 1) >> 16

        when:
            def result = encryptor.encrypt(SSRC, frame)

        then:
            1 * lib.daveEncryptorEncrypt(FAKE_HANDLE, MEDIA_TYPE_AUDIO, SSRC,
                    frame, 1, _ as byte[], 16, _ as IntByReference) >> { args ->
                args[7].setValue(0)
                return 0
            }
            !result.isPresent()
    }

    def 'setPassthroughMode forwards to native'() {
        when:
            encryptor.setPassthroughMode(true)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_HANDLE, true)

        when:
            encryptor.setPassthroughMode(false)

        then:
            1 * lib.daveEncryptorSetPassthroughMode(FAKE_HANDLE, false)
    }

    def 'assignSsrcToOpus uses correct codec constant'() {
        when:
            encryptor.assignSsrcToOpus(SSRC)

        then:
            1 * lib.daveEncryptorAssignSsrcToCodec(FAKE_HANDLE, SSRC, CODEC_OPUS)
    }

    def 'hasKeyRatchet forwards to native'() {
        when:
            def result = encryptor.hasKeyRatchet()

        then:
            1 * lib.daveEncryptorHasKeyRatchet(FAKE_HANDLE) >> true
            result
    }

    def 'isPassthroughMode forwards to native'() {
        when:
            def result = encryptor.isPassthroughMode()

        then:
            1 * lib.daveEncryptorIsPassthroughMode(FAKE_HANDLE) >> true
            result
    }

    def 'setKeyRatchet forwards to native'() {
        when:
            encryptor.setKeyRatchet(FAKE_KEY_RATCHET)

        then:
            1 * lib.daveEncryptorSetKeyRatchet(FAKE_HANDLE, FAKE_KEY_RATCHET)
    }

    def 'close destroys native handle'() {
        when:
            encryptor.close()

        then:
            1 * lib.daveEncryptorDestroy(FAKE_HANDLE)
    }

    def 'methods throw IllegalStateException after close'() {
        given:
            encryptor.close()

        when:
            encryptor.encrypt(SSRC, [0x01] as byte[])

        then:
            thrown(IllegalStateException)

        when:
            encryptor.setPassthroughMode(false)

        then:
            thrown(IllegalStateException)

        when:
            encryptor.hasKeyRatchet()

        then:
            thrown(IllegalStateException)
    }

    def 'constructor throws if native creation returns null'() {
        given:
            LibDave failingLib = Mock()
            failingLib.daveEncryptorCreate() >> null

        when:
            new DaveFrameEncryptor(failingLib)

        then:
            thrown(IllegalStateException)
    }
}
