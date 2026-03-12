package org.javacord.core.util.dave

import com.sun.jna.Pointer
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
            lib.daveCommitResultIsFailed(FAKE_HANDLE) >> true

        expect:
            result.isFailed()
    }

    def 'isIgnored delegates to native'() {
        given:
            def result = new DaveCommitResult(lib, FAKE_HANDLE)
            lib.daveCommitResultIsFailed(FAKE_HANDLE) >> false
            lib.daveCommitResultIsIgnored(FAKE_HANDLE) >> true

        expect:
            result.isIgnored()
    }

    def 'isSuccess returns true when not failed and not ignored'() {
        given:
            def result = new DaveCommitResult(lib, FAKE_HANDLE)
            lib.daveCommitResultIsFailed(FAKE_HANDLE) >> false
            lib.daveCommitResultIsIgnored(FAKE_HANDLE) >> false

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
}
