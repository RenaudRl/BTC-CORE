package dev.btc.core.monitor;

import io.sentry.Sentry;
import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.Bukkit;

/**
 * BTCCore: Sentry error monitoring integration.
 */
public class SentryManager {
    
    public static void init() {
        String dsn = BTCCoreConfig.sentryDsn;
        if (dsn == null || dsn.isEmpty()) {
            return;
        }

        try {
            Sentry.init(options -> {
                options.setDsn(dsn);
                options.setTracesSampleRate(1.0);
                options.setEnvironment("production");
                options.setRelease("btccore@" + Bukkit.getMinecraftVersion());
            });
            Bukkit.getLogger().info("[BTCCore] Sentry monitoring initialized");
        } catch (Exception e) {
            Bukkit.getLogger().warning("[BTCCore] Failed to initialize Sentry: " + e.getMessage());
        }
    }

    public static void captureException(Throwable throwable) {
        if (BTCCoreConfig.sentryDsn != null && !BTCCoreConfig.sentryDsn.isEmpty()) {
            Sentry.captureException(throwable);
        }
    }

    public static void captureMessage(String message) {
        if (BTCCoreConfig.sentryDsn != null && !BTCCoreConfig.sentryDsn.isEmpty()) {
            Sentry.captureMessage(message);
        }
    }
}

