package org.javacord.core.util.dave;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents the result of processing an MLS welcome message.
 *
 * <p>Contains access to the group roster after being welcomed into the
 * MLS group. The native result handle is freed when this object is closed.
 */
public class DaveWelcomeResult implements AutoCloseable {

    private final LibDave lib;
    private Pointer resultHandle;
    private final boolean nativeFailure;

    DaveWelcomeResult(LibDave lib, Pointer resultHandle) {
        this.lib = lib;
        this.resultHandle = resultHandle;
        this.nativeFailure = false;
    }

    private DaveWelcomeResult() {
        this.lib = null;
        this.resultHandle = null;
        this.nativeFailure = true;
    }

    static DaveWelcomeResult failed() {
        return new DaveWelcomeResult();
    }

    /**
     * Returns whether processing the welcome failed.
     *
     * @return {@code true} if the welcome could not be processed.
     */
    public boolean isFailed() {
        return nativeFailure || resultHandle == null;
    }

    /**
     * Gets the set of user IDs in the group roster from this welcome.
     *
     * @return An unmodifiable set of user ID longs in the roster.
     */
    public Set<Long> getRosterMemberIds() {
        if (nativeFailure || resultHandle == null) {
            return Collections.emptySet();
        }
        PointerByReference idsPtr = new PointerByReference();
        LongByReference lengthRef = new LongByReference();
        lib.daveWelcomeResultGetRosterMemberIds(resultHandle, idsPtr, lengthRef);

        Pointer ptr = idsPtr.getValue();
        if (ptr == null) {
            return Collections.emptySet();
        }
        long[] ids;
        try {
            // libdave allocates the array even when the roster is empty, so always free it
            long count = lengthRef.getValue();
            ids = count > 0 ? ptr.getLongArray(0, (int) count) : new long[0];
        } finally {
            lib.daveFree(ptr);
        }

        Set<Long> result = new HashSet<>();
        for (long id : ids) {
            result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public void close() {
        if (resultHandle != null && lib != null) {
            lib.daveWelcomeResultDestroy(resultHandle);
            resultHandle = null;
        }
    }
}
