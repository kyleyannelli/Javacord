package org.javacord.core.util.dave;

import com.sun.jna.Native;
import org.apache.logging.log4j.Logger;
import org.javacord.core.util.logging.LoggerUtil;

/**
 * Loads the native libdave shared library from the system library path.
 *
 * <p>The library is loaded lazily on first access. If libdave is not installed
 * on the system, voice connections requiring DAVE E2EE will fail with a clear
 * error message.
 *
 * @see <a href="https://github.com/discord/libdave">libdave on GitHub</a>
 */
public final class LibDaveLoader {

    private static final Logger logger = LoggerUtil.getLogger(LibDaveLoader.class);

    private static volatile LibDave instance;
    private static volatile boolean loadAttempted;
    private static volatile String loadError;
    @SuppressWarnings("unused")
    private static volatile LibDave.LogSinkCallback logSinkRef;

    private LibDaveLoader() {
    }

    /**
     * Gets the loaded libdave instance, or {@code null} if the library is not available.
     *
     * @return The loaded {@link LibDave} interface, or {@code null} if loading failed.
     */
    public static LibDave getInstance() {
        if (!loadAttempted) {
            synchronized (LibDaveLoader.class) {
                if (!loadAttempted) {
                    try {
                        instance = Native.load("dave", LibDave.class);
                        installLogSink(instance);
                        logger.info("Successfully loaded libdave (max protocol version: {})",
                                instance.daveMaxSupportedProtocolVersion());
                    } catch (UnsatisfiedLinkError e) {
                        loadError = e.getMessage();
                        logger.warn("libdave native library not found on system library path. "
                                + "Voice connections requiring DAVE E2EE will not work. "
                                + "Install libdave and ensure it is on your system library path "
                                + "(LD_LIBRARY_PATH on Linux, DYLD_LIBRARY_PATH on macOS, PATH on Windows). "
                                + "See https://github.com/discord/libdave for build instructions. "
                                + "Error: {}", e.getMessage());
                    }
                    loadAttempted = true;
                }
            }
        }
        return instance;
    }

    /**
     * Returns whether the libdave library is available on this system.
     *
     * @return {@code true} if libdave was loaded successfully.
     */
    public static boolean isAvailable() {
        return getInstance() != null;
    }

    /**
     * Returns the error message from the last failed load attempt, if any.
     *
     * @return The error message, or {@code null} if loading succeeded or was not attempted.
     */
    public static String getLoadError() {
        getInstance();
        return loadError;
    }

    private static void installLogSink(LibDave lib) {
        Logger nativeLogger = LoggerUtil.getLogger("org.javacord.core.util.dave.native");
        LibDave.LogSinkCallback sink = (severity, file, line, message) -> {
            switch (severity) {
                case LibDave.DAVE_LOGGING_SEVERITY_ERROR:
                    nativeLogger.error("({}) {}", file, message);
                    break;
                case LibDave.DAVE_LOGGING_SEVERITY_WARNING:
                    nativeLogger.warn("({}) {}", file, message);
                    break;
                case LibDave.DAVE_LOGGING_SEVERITY_INFO:
                    nativeLogger.debug("({}) {}", file, message);
                    break;
                case LibDave.DAVE_LOGGING_SEVERITY_VERBOSE:
                    nativeLogger.trace("({}) {}", file, message);
                    break;
                default:
                    break;
            }
        };
        logSinkRef = sink;
        lib.daveSetLogSinkCallback(sink);
    }
}
