package org.javacord.core.util.dave

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import spock.lang.Specification
import spock.lang.Subject

@Subject(DaveFrameDecryptor)
class DaveFrameDecryptorTest extends Specification {

    static final Pointer FAKE_HANDLE = new Pointer(1L)
    static final Pointer FAKE_KEY_RATCHET = new Pointer(2L)
    static final int MEDIA_TYPE_AUDIO = 0

    LibDave lib = Mock()
    DaveFrameDecryptor decryptor

    def setup() {
        lib.daveDecryptorCreate() >> FAKE_HANDLE
        decryptor = new DaveFrameDecryptor(lib)
    }

    def 'decrypt returns decrypted bytes on success'() {
        given:
            byte[] encrypted = [0xAA, 0xBB, 0xCC, 0xDD] as byte[]
            lib.daveDecryptorGetMaxPlaintextByteSize(FAKE_HANDLE, MEDIA_TYPE_AUDIO, 4) >> 16

        when:
            def result = decryptor.decrypt(encrypted)

        then:
            1 * lib.daveDecryptorDecrypt(FAKE_HANDLE, MEDIA_TYPE_AUDIO,
                    encrypted, 4, _ as byte[], 16, _ as IntByReference) >> { args ->
                args[6].setValue(3)
                return 0
            }
            result.isPresent()
            result.get().length == 3
    }

    def 'decrypt returns empty optional on non-zero result code'() {
        given:
            byte[] encrypted = [0x01] as byte[]
            lib.daveDecryptorGetMaxPlaintextByteSize(FAKE_HANDLE, MEDIA_TYPE_AUDIO, 1) >> 8

        when:
            def result = decryptor.decrypt(encrypted)

        then:
            1 * lib.daveDecryptorDecrypt(FAKE_HANDLE, MEDIA_TYPE_AUDIO,
                    encrypted, 1, _ as byte[], 8, _ as IntByReference) >> 1
            !result.isPresent()
    }

    def 'decrypt returns empty optional when bytes written is zero'() {
        given:
            byte[] encrypted = [0x01] as byte[]
            lib.daveDecryptorGetMaxPlaintextByteSize(FAKE_HANDLE, MEDIA_TYPE_AUDIO, 1) >> 8

        when:
            def result = decryptor.decrypt(encrypted)

        then:
            1 * lib.daveDecryptorDecrypt(FAKE_HANDLE, MEDIA_TYPE_AUDIO,
                    encrypted, 1, _ as byte[], 8, _ as IntByReference) >> { args ->
                args[6].setValue(0)
                return 0
            }
            !result.isPresent()
    }

    def 'transitionToKeyRatchet forwards to native'() {
        when:
            decryptor.transitionToKeyRatchet(FAKE_KEY_RATCHET)

        then:
            1 * lib.daveDecryptorTransitionToKeyRatchet(FAKE_HANDLE, FAKE_KEY_RATCHET)
    }

    def 'transitionToPassthroughMode forwards to native'() {
        when:
            decryptor.transitionToPassthroughMode(true)

        then:
            1 * lib.daveDecryptorTransitionToPassthroughMode(FAKE_HANDLE, true)

        when:
            decryptor.transitionToPassthroughMode(false)

        then:
            1 * lib.daveDecryptorTransitionToPassthroughMode(FAKE_HANDLE, false)
    }

    def 'close destroys native handle'() {
        when:
            decryptor.close()

        then:
            1 * lib.daveDecryptorDestroy(FAKE_HANDLE)
    }

    def 'methods throw IllegalStateException after close'() {
        given:
            decryptor.close()

        when:
            decryptor.decrypt([0x01] as byte[])

        then:
            thrown(IllegalStateException)

        when:
            decryptor.transitionToKeyRatchet(FAKE_KEY_RATCHET)

        then:
            thrown(IllegalStateException)

        when:
            decryptor.transitionToPassthroughMode(false)

        then:
            thrown(IllegalStateException)
    }

    def 'constructor throws if native creation returns null'() {
        given:
            LibDave failingLib = Mock()
            failingLib.daveDecryptorCreate() >> null

        when:
            new DaveFrameDecryptor(failingLib)

        then:
            thrown(IllegalStateException)
    }
}
