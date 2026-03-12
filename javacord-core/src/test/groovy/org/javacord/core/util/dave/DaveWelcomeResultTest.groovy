package org.javacord.core.util.dave

import com.sun.jna.Pointer
import spock.lang.Specification
import spock.lang.Subject

@Subject(DaveWelcomeResult)
class DaveWelcomeResultTest extends Specification {

    static final Pointer FAKE_HANDLE = new Pointer(1L)

    LibDave lib = Mock()

    def 'failed factory returns result where isFailed is true'() {
        given:
            def result = DaveWelcomeResult.failed()

        expect:
            result.isFailed()
    }

    def 'isFailed returns false with valid native handle'() {
        given:
            def result = new DaveWelcomeResult(lib, FAKE_HANDLE)

        expect:
            !result.isFailed()
    }

    def 'close destroys native handle'() {
        given:
            def result = new DaveWelcomeResult(lib, FAKE_HANDLE)

        when:
            result.close()

        then:
            1 * lib.daveWelcomeResultDestroy(FAKE_HANDLE)
    }

    def 'close on failed factory result does not call native destroy'() {
        given:
            def result = DaveWelcomeResult.failed()

        when:
            result.close()

        then:
            0 * lib.daveWelcomeResultDestroy(_)
    }

    def 'getRosterMemberIds returns empty set for failed result'() {
        given:
            def result = DaveWelcomeResult.failed()

        expect:
            result.getRosterMemberIds().isEmpty()
    }
}
