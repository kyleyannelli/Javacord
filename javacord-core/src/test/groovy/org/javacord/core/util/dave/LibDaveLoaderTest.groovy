package org.javacord.core.util.dave

import spock.lang.Specification
import spock.lang.Subject

@Subject(LibDaveLoader)
class LibDaveLoaderTest extends Specification {

    def 'getInstance returns null when native library is not present'() {
        expect:
            LibDaveLoader.getInstance() == null
    }

    def 'isAvailable returns false when native library is not present'() {
        expect:
            !LibDaveLoader.isAvailable()
    }

    def 'getLoadError returns non-null error message after failed load'() {
        expect:
            LibDaveLoader.getLoadError() != null
            LibDaveLoader.getLoadError().length() > 0
    }
}
