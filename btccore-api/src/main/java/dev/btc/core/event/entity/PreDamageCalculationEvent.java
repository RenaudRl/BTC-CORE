package dev.btc.core.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fires before damage calculation, allowing modification of damage sources or amounts.
 * This is primarily used for RPG stats like Critical Hits, Damage Reduction, and Penetration.
 */
public class PreDamageCalculationEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Entity victim;
    private final Entity damager;
    private double baseDamage;
    private double damageMultiplier = 1.0;

    public PreDamageCalculationEvent(@NotNull Entity victim, @org.jetbrains.annotations.Nullable Entity damager, double baseDamage) {
        this.victim = victim;
        this.damager = damager;
        this.baseDamage = baseDamage;
    }

    @NotNull
    public Entity getVictim() {
        return victim;
    }

    @org.jetbrains.annotations.Nullable
    public Entity getDamager() {
        return damager;
    }

    public double getBaseDamage() {
        return baseDamage;
    }

    public void setBaseDamage(double baseDamage) {
        this.baseDamage = baseDamage;
    }

    public double getDamageMultiplier() {
        return damageMultiplier;
    }

    public void setDamageMultiplier(double damageMultiplier) {
        this.damageMultiplier = damageMultiplier;
    }

    public double getFinalDamage() {
        return baseDamage * damageMultiplier;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

