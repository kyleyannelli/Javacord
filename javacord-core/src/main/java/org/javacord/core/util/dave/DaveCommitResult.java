package org.javacord.core.util.dave;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents the result of processing an MLS commit message.
 *
 * <p>Contains information about whether the commit was successful, ignored,
 * or failed, and provides access to the updated roster of group members.
 * The native result handle is freed when this object is closed.
 */
public class DaveCommitResult implements AutoCloseable {

    private final LibDave lib;
    private Pointer resultHandle;
    private final boolean nativeFailure;

    DaveCommitResult(LibDave lib, Pointer resultHandle) {
        this.lib = lib;
        this.resultHandle = resultHandle;
        this.nativeFailure = false;
    }

    private DaveCommitResult() {
        this.lib = null;
        this.resultHandle = null;
        this.nativeFailure = true;
    }

    static DaveCommitResult failed() {
        return new DaveCommitResult();
    }

    /**
     * Returns whether processing the commit failed entirely.
     *
     * @return {@code true} if the commit could not be processed.
     */
    public boolean isFailed() {
        if (nativeFailure) {
            return true;
        }
        return resultHandle != null && lib.daveCommitResultIsFailed(resultHandle);
    }

    /**
     * Returns whether the commit should be silently ignored.
     *
     * @return {@code true} if the commit is not relevant and should be skipped.
     */
    public boolean isIgnored() {
        if (nativeFailure || resultHandle == null) {
            return false;
        }
        return lib.daveCommitResultIsIgnored(resultHandle);
    }

    /**
     * Returns whether the commit was processed successfully.
     *
     * @return {@code true} if the commit was applied to the local MLS group state.
     */
    public boolean isSuccess() {
        return !isFailed() && !isIgnored();
    }

    /**
     * Gets the set of user IDs in the group roster after this commit.
     *
     * @return An unmodifiable set of user ID longs in the updated roster.
     */
    public Set<Long> getRosterMemberIds() {
        if (nativeFailure || resultHandle == null) {
            return Collections.emptySet();
        }
        PointerByReference idsPtr = new PointerByReference();
        IntByReference lengthRef = new IntByReference();
        lib.daveCommitResultGetRosterMemberIds(resultHandle, idsPtr, lengthRef);

        Pointer ptr = idsPtr.getValue();
        int count = lengthRef.getValue();
        if (ptr == null || count <= 0) {
            return Collections.emptySet();
        }

        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = ptr.getLong((long) i * Long.BYTES);
        }
        lib.daveFree(ptr);

        Set<Long> result = new HashSet<>();
        for (long id : ids) {
            result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public void close() {
        if (resultHandle != null && lib != null) {
            lib.daveCommitResultDestroy(resultHandle);
            resultHandle = null;
        }
    }
}
