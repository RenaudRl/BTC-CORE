package dev.btc.core.plugin.mocks;

import com.destroystokyo.paper.SkinParts;
import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.world.damagesource.CombatEntry;
import io.papermc.paper.world.damagesource.FallLocationType;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.block.Biome;
import org.bukkit.damage.DamageEffect;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.Predicate;

public class MockInternalAPIBridge implements InternalAPIBridge {

    @Override
    public DamageEffect getDamageEffect(String key) {
        return null;
    }

    @Override
    public Biome constructLegacyCustomBiome() {
        // Return a proxy to avoid any class loading or initialization issues
        return (Biome) java.lang.reflect.Proxy.newProxyInstance(
                Biome.class.getClassLoader(),
                new Class[] { Biome.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getKey"))
                        return null;
                    if (method.getName().equals("name"))
                        return "CUSTOM";
                    return null;
                });
    }

    @Override
    public CombatEntry createCombatEntry(LivingEntity entity, DamageSource damageSource, float damage) {
        return null;
    }

    @Override
    public CombatEntry createCombatEntry(DamageSource damageSource, float damage,
            @Nullable FallLocationType fallLocationType, float fallDistance) {
        return null;
    }

    @Override
    public Predicate<CommandSourceStack> restricted(Predicate<CommandSourceStack> predicate) {
        return predicate;
    }

    @Override
    public ResolvableProfile defaultMannequinProfile() {
        return null; // Or mock if needed
    }

    @Override
    public SkinParts.Mutable allSkinParts() {
        return null; // Or mock
    }

    @Override
    public Component defaultMannequinDescription() {
        return Component.text("Mannequin");
    }

    @Override
    public <MODERN, LEGACY> GameRule<LEGACY> legacyGameRuleBridge(GameRule<MODERN> rule,
            Function<LEGACY, MODERN> fromLegacyToModern, Function<MODERN, LEGACY> toLegacyFromModern,
            Class<LEGACY> legacyClass) {
        return null;
    }
}

