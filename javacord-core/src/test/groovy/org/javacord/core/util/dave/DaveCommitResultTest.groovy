package org.javacord.core.util.dave

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import spock.lang.Specification
import spock.lang.Subject

@Subject(DaveCommitResult)
class DaveCommitResultTest extends Specification {

    static final Pointer FAKE_HANDLE = new Pointer(1L)

    LibDave lib = Mock()

    def 'failed factory returns result where isFailed is true'() {
        given:
            def result = DaveCommitResult.failed()

        expect:
            result.isFailed()
            !result.isIgnored()
            !result.isSuccess()
    }

    def 'isFailed delegates to native when not a native failure'() {
        given:
            def result = new DaveCommitResult(lib, FAKE_HANDLE)
            lib.daveCommitResultIsFailed(FAKE_HANDLE) >> (byte) 1

        expect:
            result.isFailed()
    }

    def 'isIgnored delegates to native'() {
        given:
            def result = new DaveCommitResult(lib, FAKE_HANDLE)
            lib.daveCommitResultIsFailed(FAKE_HANDLE) >> (byte) 0
            lib.daveCommitResultIsIgnored(FAKE_HANDLE) >> (byte) 1

        expect:
            result.isIgnored()
    }

    def 'isSuccess returns true when not failed and not ignored'() {
        given:
            def result = new DaveCommitResult(lib, FAKE_HANDLE)
            lib.daveCommitResultIsFailed(FAKE_HANDLE) >> (byte) 0
            lib.daveCommitResultIsIgnored(FAKE_HANDLE) >> (byte) 0

        expect:
            result.isSuccess()
    }

    def 'close destroys native handle'() {
        given:
            def result = new DaveCommitResult(lib, FAKE_HANDLE)

        when:
            result.close()

        then:
            1 * lib.daveCommitResultDestroy(FAKE_HANDLE)
    }

    def 'close on failed factory result does not call native destroy'() {
        given:
            def result = DaveCommitResult.failed()

        when:
            result.close()

        then:
            0 * lib.daveCommitResultDestroy(_)
    }

    def 'getRosterMemberIds returns empty set for failed result'() {
        given:
            def result = DaveCommitResult.failed()

        expect:
            result.getRosterMemberIds().isEmpty()
    }

    def 'getRosterMemberIds reads uint64 ids and frees the native array'() {
        given:
            Memory ids = new Memory(16)
            ids.setLong(0, 111L)
            ids.setLong(8, 222L)
            def result = new DaveCommitResult(lib, FAKE_HANDLE)

        when:
            def roster = result.getRosterMemberIds()

        then:
            1 * lib.daveCommitResultGetRosterMemberIds(FAKE_HANDLE, _ as PointerByReference, _ as LongByReference) >> { args ->
                args[1].setValue(ids)
                args[2].setValue(2L)
            }
            1 * lib.daveFree(ids)
            roster == [111L, 222L] as Set
    }

    def 'getRosterMemberIds frees the native array even when the roster is empty'() {
        given:
            Memory ids = new Memory(1)
            def result = new DaveCommitResult(lib, FAKE_HANDLE)

        when:
            def roster = result.getRosterMemberIds()

        then:
            1 * lib.daveCommitResultGetRosterMemberIds(FAKE_HANDLE, _ as PointerByReference, _ as LongByReference) >> { args ->
                args[1].setValue(ids)
                args[2].setValue(0L)
            }
            1 * lib.daveFree(ids)
            roster.isEmpty()
    }
}
