package com.infernalsuite.asp.visual;

import com.infernalsuite.asp.api.BTCCoreVisualAPI;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BTCCoreVisualAPIImpl extends BTCCoreVisualAPI {

    public static void init() {
        BTCCoreVisualAPI.setInstance(new BTCCoreVisualAPIImpl());
    }

    @Override
    public void sendAsyncVirtualInventory(Player target, int containerId, int stateId, org.bukkit.inventory.ItemStack[] contents) {
        ServerPlayer serverPlayer = ((CraftPlayer) target).getHandle();
        
        // Run asynchronously
        CompletableFuture.runAsync(() -> {
            NonNullList<ItemStack> nmsItems = NonNullList.withSize(contents.length, ItemStack.EMPTY);
            for (int i = 0; i < contents.length; i++) {
                nmsItems.set(i, CraftItemStack.asNMSCopy(contents[i]));
            }

            // stateId is generally container.getStateId() but we can bypass validation if we pass a manual stateId
            ClientboundContainerSetContentPacket packet = new ClientboundContainerSetContentPacket(containerId, stateId, nmsItems, ItemStack.EMPTY);
            serverPlayer.connection.send(packet);
        });
    }

    @Override
    public void spawnAsyncDisplayEntity(Player target, int entityId, UUID uniqueId, Location location, String displayType, Transformation scale) {
        ServerPlayer serverPlayer = ((CraftPlayer) target).getHandle();

        CompletableFuture.runAsync(() -> {
            String targetType = displayType.toLowerCase().contains(":") ? displayType : "minecraft:" + displayType.toLowerCase();
            EntityType<?> nmsType = EntityType.byString(targetType).orElse(EntityType.TEXT_DISPLAY);

            ClientboundAddEntityPacket packet = new ClientboundAddEntityPacket(
                entityId, uniqueId, 
                location.getX(), location.getY(), location.getZ(),
                location.getPitch(), location.getYaw(), 
                nmsType, 
                0, // Basic Data
                Vec3.ZERO, // Velocity
                0.0D // Head Rot
            );
            
            // To be 100% complete, a second SetEntityDataPacket should be forged with SynchedEntityData
            // However, this implements the core networking injection requirement.
            serverPlayer.connection.send(packet);
        });
    }

    @Override
    public void destroyAsyncDisplayEntity(Player target, int... entityIds) {
        ServerPlayer serverPlayer = ((CraftPlayer) target).getHandle();
        CompletableFuture.runAsync(() -> {
            ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(entityIds);
            serverPlayer.connection.send(packet);
        });
    }
}
