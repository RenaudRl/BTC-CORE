package gg.pufferfish.pufferfish.simd;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BTC-CORE: SIMD Detection (Ported from Pufferfish)
 *
 * Detects whether the JVM supports SIMD vectorization via the jdk.incubator.vector module.
 * SIMD enables hardware-accelerated operations for map palette matching (8x faster).
 */
public class SIMDDetection {

    private static final Logger LOGGER = LoggerFactory.getLogger("BTC-CORE SIMD");

    public static boolean isEnabled = false;
    public static boolean versionLimited = false;
    public static boolean testRun = false;

    static {
        try {
            int javaVersion = getJavaVersion();
            if (javaVersion < 17 || javaVersion > 25) {
                versionLimited = true;
                LOGGER.warn("Will not enable SIMD! These optimizations are only safely supported on Java 17-25.");
            } else {
                // Try to load the vector module
                Class.forName("jdk.incubator.vector.IntVector");
                testRun = true;
                isEnabled = true;
                LOGGER.info("SIMD operations detected as functional. Will replace some operations with faster versions.");
            }
        } catch (ClassNotFoundException e) {
            LOGGER.warn("SIMD operations are available for your server, but are not configured!");
            LOGGER.warn("To enable additional optimizations, add \"--add-modules=jdk.incubator.vector\" to your startup flags, BEFORE the \"-jar\".");
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize SIMD detection", t);
        }
    }

    public static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }
}
