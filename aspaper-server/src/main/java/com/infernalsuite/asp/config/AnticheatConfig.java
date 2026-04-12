package com.infernalsuite.asp.config;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Native Async Anticheat Configuration Manager
 * Handles the configuration for the high-performance async packet validation system.
 */
public final class AnticheatConfig {

    private static File configFile;
    private static YamlConfiguration config;

    public static int version;
    public static boolean verbose;

    // Async Network Engine
    public static boolean asyncPacketValidationEnabled = true;
    public static int asyncValidationThreads = 2; // Default to 2 dedicated checking threads

    // Reach Validation
    public static boolean reachCheckEnabled = true;
    public static double reachMaxDistanceSurvival = 3.0; // Standard Vanilla
    public static double reachMaxDistanceCreative = 5.0; 
    public static boolean reachStrictHitboxMath = true;
    public static int reachViolationAction = 0; // 0 = Cancel, 1 = Cancel + Log, 2 = Kick

    // Velocity / Movement Validation
    public static boolean velocityCheckEnabled = true;
    public static double velocityMaxDeltaXZ = 0.8;
    public static double velocityMaxDeltaY = 1.2;
    public static int velocityViolationAction = 0; // 0 = Teleport back, 1 = Teleport back + Log, 2 = Kick

    public static void init(File file) {
        AnticheatConfig.configFile = file;
        AnticheatConfig.config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException ignore) {
        } catch (InvalidConfigurationException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not load anticheat.yml, please correct your syntax errors", ex);
            throw new RuntimeException(ex);
        }
        config.options().copyDefaults(true);

        version = getInt("config-version", 1);
        set("config-version", 1);

        verbose = getBoolean("verbose", false);

        // Load Settings
        loadAsyncSettings();
        loadReachSettings();
        loadVelocitySettings();

        try {
            config.save(configFile);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save anticheat.yml", ex);
        }
    }

    private static void loadAsyncSettings() {
        asyncPacketValidationEnabled = getBoolean("engine.async-packet-validation.enabled", true);
        asyncValidationThreads = getInt("engine.async-packet-validation.threads", 2);
    }

    private static void loadReachSettings() {
        reachCheckEnabled = getBoolean("checks.reach.enabled", true);
        reachMaxDistanceSurvival = getDouble("checks.reach.max-distance-survival", 3.0);
        reachMaxDistanceCreative = getDouble("checks.reach.max-distance-creative", 5.0);
        reachStrictHitboxMath = getBoolean("checks.reach.strict-hitbox-math", true);
        reachViolationAction = getInt("checks.reach.violation-action", 0);
    }

    private static void loadVelocitySettings() {
        velocityCheckEnabled = getBoolean("checks.velocity.enabled", true);
        velocityMaxDeltaXZ = getDouble("checks.velocity.max-delta-xz", 0.8);
        velocityMaxDeltaY = getDouble("checks.velocity.max-delta-y", 1.2);
        velocityViolationAction = getInt("checks.velocity.violation-action", 0);
    }

    private static void set(String path, Object val) {
        config.addDefault(path, val);
        config.set(path, val);
    }

    private static boolean getBoolean(String path, boolean def) {
        config.addDefault(path, def);
        return config.getBoolean(path, config.getBoolean(path));
    }

    private static int getInt(String path, int def) {
        config.addDefault(path, def);
        return config.getInt(path, config.getInt(path));
    }

    private static double getDouble(String path, double def) {
        config.addDefault(path, def);
        return config.getDouble(path, config.getDouble(path));
    }

    private static String getString(String path, String def) {
        config.addDefault(path, def);
        return config.getString(path, config.getString(path));
    }
}
