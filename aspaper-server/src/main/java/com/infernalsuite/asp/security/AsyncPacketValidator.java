package com.infernalsuite.asp.security;

import com.infernalsuite.asp.config.AnticheatConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * BTC-CORE Native Async Anticheat Validator
 * This class offloads basic combat (Reach) and movement (Velocity) 
 * validation to a dedicated thread pool to preserve main thread performance.
 */
public class AsyncPacketValidator {

    private static ExecutorService validatorPool;

    public static void init() {
        if (AnticheatConfig.asyncPacketValidationEnabled && validatorPool == null) {
            validatorPool = Executors.newFixedThreadPool(AnticheatConfig.asyncValidationThreads, r -> {
                Thread thread = new Thread(r, "BTC-CORE-AsyncValidator");
                thread.setDaemon(true);
                return thread;
            });
            Bukkit.getLogger().info("[BTC-CORE] Async Packet Validator initialized with " + AnticheatConfig.asyncValidationThreads + " threads.");
        }
    }

    public static void validateReach(ServerPlayer player, Entity target, double originX, double originY, double originZ) {
        if (!AnticheatConfig.asyncPacketValidationEnabled || validatorPool == null) return;
        if (!AnticheatConfig.reachCheckEnabled || player.getBukkitEntity().hasPermission("btccore.anticheat.bypass")) return; // By-pass for OP

        // Snapshot target AABB concurrently (Safe as long as we only read)
        AABB targetBox = target.getBoundingBox();

        validatorPool.submit(() -> {
            try {
                double maxReach = player.getAbilities().instabuild ? AnticheatConfig.reachMaxDistanceCreative : AnticheatConfig.reachMaxDistanceSurvival;
                
                // Distance to hitbox logic
                Vec3 eyePos = new Vec3(originX, originY + player.getEyeHeight(), originZ);
                
                double distance = 0.0;
                if (AnticheatConfig.reachStrictHitboxMath) {
                    distance = Math.max(0.0, targetBox.distanceToSqr(eyePos)); // Approx closest point
                } else {
                    distance = eyePos.distanceToSqr(target.position());
                }

                if (distance > (maxReach * maxReach) + 0.5) { // Small buffer for latency
                    handleViolation(player, "Reach", "Distance: " + Math.sqrt(distance) + " > " + maxReach, AnticheatConfig.reachViolationAction, player.getX(), player.getY(), player.getZ());
                }
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, "[BTC-CORE] Error in Async Reach Validation", e);
            }
        });
    }

    public static void validateVelocity(ServerPlayer player, double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        if (!AnticheatConfig.asyncPacketValidationEnabled || validatorPool == null) return;
        if (!AnticheatConfig.velocityCheckEnabled || player.getBukkitEntity().hasPermission("btccore.anticheat.bypass")) return;

        validatorPool.submit(() -> {
            try {
                double deltaX = Math.abs(toX - fromX);
                double deltaY = Math.abs(toY - fromY);
                double deltaZ = Math.abs(toZ - fromZ);

                // Ignore if in vehicle or elytra
                if (player.isPassenger() || player.isFallFlying()) return;

                if (deltaX > AnticheatConfig.velocityMaxDeltaXZ || deltaZ > AnticheatConfig.velocityMaxDeltaXZ || deltaY > AnticheatConfig.velocityMaxDeltaY) {
                    // Small heuristic to ignore heavy drops
                    if (deltaY < -3.0) return; 

                    handleViolation(player, "Velocity/Speed", String.format("Delta XZ: %.2f, %.2f | Y: %.2f", deltaX, deltaZ, deltaY), AnticheatConfig.velocityViolationAction, fromX, fromY, fromZ);
                }
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, "[BTC-CORE] Error in Async Velocity Validation", e);
            }
        });
    }

    private static void handleViolation(ServerPlayer player, String type, String details, int actionLevel, double fallbackX, double fallbackY, double fallbackZ) {
        if (!com.infernalsuite.asp.config.BTCCoreConfig.sentinelEnabled) return;

        if (actionLevel >= 1) {
            String log = ChatColor.DARK_RED + "[Sentinel] " + ChatColor.GOLD + player.getScoreboardName() + ChatColor.RED + " failed " + type + " check! " + ChatColor.GRAY + details;
            
            // Console warning
            Bukkit.getLogger().warning(ChatColor.stripColor(log));
            
            // Staff alerts
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                if (SentinelCommand.shouldReceiveAlerts(online)) {
                    online.sendMessage(log);
                }
            }

            if (com.infernalsuite.asp.config.BTCCoreConfig.sentinelMysqlLogging) {
                NativeAnticheatDB.reportViolation(player.getUUID().toString(), player.getScoreboardName(), type, details);
            }
        }
        if (actionLevel >= 2) {
            // Setback synchronisé sur le thread principal de Folia
            player.level().getServer().execute(() -> {
                player.connection.teleport(fallbackX, fallbackY, fallbackZ, player.getYRot(), player.getXRot());
            });
        }
    }
}
