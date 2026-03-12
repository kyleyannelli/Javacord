package org.javacord.core.util.dave

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import spock.lang.Specification
import spock.lang.Subject

@Subject(DaveSession)
class DaveSessionTest extends Specification {

    static final Pointer FAKE_HANDLE = new Pointer(1L)
    static final long GROUP_ID = 123456789L
    static final String SELF_USER_ID = '987654321'
    static final int PROTOCOL_VERSION = 1

    LibDave lib = Mock()
    DaveSession session

    def setup() {
        lib.daveSessionCreate(_, _, _, _) >> FAKE_HANDLE
        session = new DaveSession(lib)
    }

    def 'init forwards to native with correct arguments'() {
        when:
            session.init(PROTOCOL_VERSION, GROUP_ID, SELF_USER_ID)

        then:
            1 * lib.daveSessionInit(FAKE_HANDLE, { it == (short) PROTOCOL_VERSION }, GROUP_ID, SELF_USER_ID)
    }

    def 'reset forwards to native'() {
        when:
            session.reset()

        then:
            1 * lib.daveSessionReset(FAKE_HANDLE)
    }

    def 'setProtocolVersion forwards to native'() {
        when:
            session.setProtocolVersion(2)

        then:
            1 * lib.daveSessionSetProtocolVersion(FAKE_HANDLE, { it == (short) 2 })
    }

    def 'getProtocolVersion returns native value'() {
        when:
            def version = session.getProtocolVersion()

        then:
            1 * lib.daveSessionGetProtocolVersion(FAKE_HANDLE) >> (short) 1
            version == 1
    }

    def 'setExternalSender forwards bytes and length'() {
        given:
            byte[] data = [0x01, 0x02, 0x03] as byte[]

        when:
            session.setExternalSender(data)

        then:
            1 * lib.daveSessionSetExternalSender(FAKE_HANDLE, data, 3)
    }

    def 'getMarshalledKeyPackage returns data and frees native memory'() {
        given:
            byte[] expectedData = [0x10, 0x20, 0x30] as byte[]
            Memory keyMemory = new Memory(expectedData.length)
            keyMemory.write(0, expectedData, 0, expectedData.length)

        when:
            def result = session.getMarshalledKeyPackage()

        then:
            1 * lib.daveSessionGetMarshalledKeyPackage(FAKE_HANDLE, _ as PointerByReference, _ as IntByReference) >> { args ->
                args[1].setValue(keyMemory)
                args[2].setValue(expectedData.length)
            }
            1 * lib.daveFree(_)
            result.isPresent()
            result.get() == expectedData
    }

    def 'getMarshalledKeyPackage returns empty when pointer is null'() {
        when:
            def result = session.getMarshalledKeyPackage()

        then:
            1 * lib.daveSessionGetMarshalledKeyPackage(FAKE_HANDLE, _ as PointerByReference, _ as IntByReference)
            !result.isPresent()
    }

    def 'getMarshalledKeyPackage returns empty when length is zero'() {
        given:
            Memory keyMemory = new Memory(1)

        when:
            def result = session.getMarshalledKeyPackage()

        then:
            1 * lib.daveSessionGetMarshalledKeyPackage(FAKE_HANDLE, _ as PointerByReference, _ as IntByReference) >> { args ->
                args[1].setValue(keyMemory)
                args[2].setValue(0)
            }
            !result.isPresent()
    }

    def 'processProposals returns commit bytes when available'() {
        given:
            byte[] proposals = [0x01, 0x02] as byte[]
            Set<String> users = ['111', '222'] as Set
            byte[] expectedCommit = [0xAA, 0xBB, 0xCC, 0xDD] as byte[]
            Memory commitMemory = new Memory(expectedCommit.length)
            commitMemory.write(0, expectedCommit, 0, expectedCommit.length)

        when:
            def result = session.processProposals(proposals, users)

        then:
            1 * lib.daveSessionProcessProposals(FAKE_HANDLE, proposals, 2,
                    _ as String[], 2, _ as PointerByReference, _ as IntByReference) >> { args ->
                args[5].setValue(commitMemory)
                args[6].setValue(expectedCommit.length)
            }
            1 * lib.daveFree(_)
            result.isPresent()
            result.get() == expectedCommit
    }

    def 'processProposals returns empty when no commit is needed'() {
        given:
            byte[] proposals = [0x01] as byte[]
            Set<String> users = [] as Set

        when:
            def result = session.processProposals(proposals, users)

        then:
            1 * lib.daveSessionProcessProposals(FAKE_HANDLE, proposals, 1,
                    _ as String[], 0, _ as PointerByReference, _ as IntByReference)
            !result.isPresent()
    }

    def 'processCommit returns result with native handle'() {
        given:
            byte[] commit = [0x01] as byte[]
            Pointer resultHandle = new Pointer(30L)
            lib.daveSessionProcessCommit(FAKE_HANDLE, commit, 1) >> resultHandle

        when:
            def result = session.processCommit(commit)

        then:
            result != null
            !result.isFailed()
    }

    def 'processCommit returns failed result when native returns null'() {
        given:
            byte[] commit = [0x01] as byte[]
            lib.daveSessionProcessCommit(FAKE_HANDLE, commit, 1) >> null

        when:
            def result = session.processCommit(commit)

        then:
            result.isFailed()
    }

    def 'processWelcome returns result with native handle'() {
        given:
            byte[] welcome = [0x01] as byte[]
            Set<String> users = ['111'] as Set
            Pointer resultHandle = new Pointer(40L)
            lib.daveSessionProcessWelcome(FAKE_HANDLE, welcome, 1, _ as String[], 1) >> resultHandle

        when:
            def result = session.processWelcome(welcome, users)

        then:
            result != null
            !result.isFailed()
    }

    def 'processWelcome returns failed result when native returns null'() {
        given:
            byte[] welcome = [0x01] as byte[]
            Set<String> users = [] as Set
            lib.daveSessionProcessWelcome(FAKE_HANDLE, welcome, 1, _ as String[], 0) >> null

        when:
            def result = session.processWelcome(welcome, users)

        then:
            result.isFailed()
    }

    def 'getKeyRatchet forwards to native'() {
        given:
            Pointer ratchet = new Pointer(50L)
            lib.daveSessionGetKeyRatchet(FAKE_HANDLE, SELF_USER_ID) >> ratchet

        when:
            def result = session.getKeyRatchet(SELF_USER_ID)

        then:
            result == ratchet
    }

    def 'close destroys session handle'() {
        when:
            session.close()

        then:
            1 * lib.daveSessionDestroy(FAKE_HANDLE)
    }

    def 'methods throw IllegalStateException after close'() {
        given:
            session.close()

        when:
            session.init(1, 1L, 'test')

        then:
            thrown(IllegalStateException)

        when:
            session.reset()

        then:
            thrown(IllegalStateException)

        when:
            session.setExternalSender([0x01] as byte[])

        then:
            thrown(IllegalStateException)
    }

    def 'constructor throws if native creation returns null'() {
        given:
            LibDave failingLib = Mock()
            failingLib.daveSessionCreate(_, _, _, _) >> null

        when:
            new DaveSession(failingLib)

        then:
            thrown(IllegalStateException)
    }
}
