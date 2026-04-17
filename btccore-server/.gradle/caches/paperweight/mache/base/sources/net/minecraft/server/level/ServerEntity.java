package net.minecraft.server.level;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ServerEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TOLERANCE_LEVEL_ROTATION = 1;
    private static final double TOLERANCE_LEVEL_POSITION = 7.6293945E-6F;
    public static final int FORCED_POS_UPDATE_PERIOD = 60;
    private static final int FORCED_TELEPORT_PERIOD = 400;
    private final ServerLevel level;
    private final Entity entity;
    private final int updateInterval;
    private final boolean trackDelta;
    private final ServerEntity.Synchronizer synchronizer;
    private final VecDeltaCodec positionCodec = new VecDeltaCodec();
    private byte lastSentYRot;
    private byte lastSentXRot;
    private byte lastSentYHeadRot;
    private Vec3 lastSentMovement;
    private int tickCount;
    private int teleportDelay;
    private List<Entity> lastPassengers = Collections.emptyList();
    private boolean wasRiding;
    private boolean wasOnGround;
    private @Nullable List<SynchedEntityData.DataValue<?>> trackedDataValues;

    public ServerEntity(ServerLevel level, Entity entity, int updateInterval, boolean trackDelta, ServerEntity.Synchronizer synchronizer) {
        this.level = level;
        this.synchronizer = synchronizer;
        this.entity = entity;
        this.updateInterval = updateInterval;
        this.trackDelta = trackDelta;
        this.positionCodec.setBase(entity.trackingPosition());
        this.lastSentMovement = entity.getDeltaMovement();
        this.lastSentYRot = Mth.packDegrees(entity.getYRot());
        this.lastSentXRot = Mth.packDegrees(entity.getXRot());
        this.lastSentYHeadRot = Mth.packDegrees(entity.getYHeadRot());
        this.wasOnGround = entity.onGround();
        this.trackedDataValues = entity.getEntityData().getNonDefaultValues();
    }

    public void sendChanges() {
        this.entity.updateDataBeforeSync();
        List<Entity> passengers = this.entity.getPassengers();
        if (!passengers.equals(this.lastPassengers)) {
            this.synchronizer
                .sendToTrackingPlayersFiltered(
                    new ClientboundSetPassengersPacket(this.entity),
                    serverPlayer1 -> passengers.contains(serverPlayer1) == this.lastPassengers.contains(serverPlayer1)
                );
            this.lastPassengers = passengers;
        }

        if (this.entity instanceof ItemFrame itemFrame && this.tickCount % 10 == 0) {
            ItemStack item = itemFrame.getItem();
            if (item.getItem() instanceof MapItem) {
                MapId mapId = item.get(DataComponents.MAP_ID);
                MapItemSavedData savedData = MapItem.getSavedData(mapId, this.level);
                if (savedData != null) {
                    for (ServerPlayer serverPlayer : this.level.players()) {
                        savedData.tickCarriedBy(serverPlayer, item);
                        Packet<?> updatePacket = savedData.getUpdatePacket(mapId, serverPlayer);
                        if (updatePacket != null) {
                            serverPlayer.connection.send(updatePacket);
                        }
                    }
                }
            }

            this.sendDirtyEntityData();
        }

        if (this.tickCount % this.updateInterval == 0 || this.entity.needsSync || this.entity.getEntityData().isDirty()) {
            byte b = Mth.packDegrees(this.entity.getYRot());
            byte b1 = Mth.packDegrees(this.entity.getXRot());
            boolean flag = Math.abs(b - this.lastSentYRot) >= 1 || Math.abs(b1 - this.lastSentXRot) >= 1;
            if (this.entity.isPassenger()) {
                if (flag) {
                    this.synchronizer.sendToTrackingPlayers(new ClientboundMoveEntityPacket.Rot(this.entity.getId(), b, b1, this.entity.onGround()));
                    this.lastSentYRot = b;
                    this.lastSentXRot = b1;
                }

                this.positionCodec.setBase(this.entity.trackingPosition());
                this.sendDirtyEntityData();
                this.wasRiding = true;
            } else if (this.entity instanceof AbstractMinecart abstractMinecart
                && abstractMinecart.getBehavior() instanceof NewMinecartBehavior newMinecartBehavior) {
                this.handleMinecartPosRot(newMinecartBehavior, b, b1, flag);
            } else {
                this.teleportDelay++;
                Vec3 vec3 = this.entity.trackingPosition();
                boolean flag1 = this.positionCodec.delta(vec3).lengthSqr() >= 7.6293945E-6F;
                Packet<ClientGamePacketListener> packet = null;
                boolean flag2 = flag1 || this.tickCount % 60 == 0;
                boolean flag3 = false;
                boolean flag4 = false;
                long l = this.positionCodec.encodeX(vec3);
                long l1 = this.positionCodec.encodeY(vec3);
                long l2 = this.positionCodec.encodeZ(vec3);
                boolean flag5 = l < -32768L || l > 32767L || l1 < -32768L || l1 > 32767L || l2 < -32768L || l2 > 32767L;
                if (this.entity.getRequiresPrecisePosition()
                    || flag5
                    || this.teleportDelay > 400
                    || this.wasRiding
                    || this.wasOnGround != this.entity.onGround()) {
                    this.wasOnGround = this.entity.onGround();
                    this.teleportDelay = 0;
                    packet = ClientboundEntityPositionSyncPacket.of(this.entity);
                    flag3 = true;
                    flag4 = true;
                } else if ((!flag2 || !flag) && !(this.entity instanceof AbstractArrow)) {
                    if (flag2) {
                        packet = new ClientboundMoveEntityPacket.Pos(this.entity.getId(), (short)l, (short)l1, (short)l2, this.entity.onGround());
                        flag3 = true;
                    } else if (flag) {
                        packet = new ClientboundMoveEntityPacket.Rot(this.entity.getId(), b, b1, this.entity.onGround());
                        flag4 = true;
                    }
                } else {
                    packet = new ClientboundMoveEntityPacket.PosRot(this.entity.getId(), (short)l, (short)l1, (short)l2, b, b1, this.entity.onGround());
                    flag3 = true;
                    flag4 = true;
                }

                if (this.entity.needsSync || this.trackDelta || this.entity instanceof LivingEntity && ((LivingEntity)this.entity).isFallFlying()) {
                    Vec3 deltaMovement = this.entity.getDeltaMovement();
                    double d = deltaMovement.distanceToSqr(this.lastSentMovement);
                    if (d > 1.0E-7 || d > 0.0 && deltaMovement.lengthSqr() == 0.0) {
                        this.lastSentMovement = deltaMovement;
                        if (this.entity instanceof AbstractHurtingProjectile abstractHurtingProjectile) {
                            this.synchronizer
                                .sendToTrackingPlayers(
                                    new ClientboundBundlePacket(
                                        List.of(
                                            new ClientboundSetEntityMotionPacket(this.entity.getId(), this.lastSentMovement),
                                            new ClientboundProjectilePowerPacket(abstractHurtingProjectile.getId(), abstractHurtingProjectile.accelerationPower)
                                        )
                                    )
                                );
                        } else {
                            this.synchronizer.sendToTrackingPlayers(new ClientboundSetEntityMotionPacket(this.entity.getId(), this.lastSentMovement));
                        }
                    }
                }

                if (packet != null) {
                    this.synchronizer.sendToTrackingPlayers(packet);
                }

                this.sendDirtyEntityData();
                if (flag3) {
                    this.positionCodec.setBase(vec3);
                }

                if (flag4) {
                    this.lastSentYRot = b;
                    this.lastSentXRot = b1;
                }

                this.wasRiding = false;
            }

            byte b2 = Mth.packDegrees(this.entity.getYHeadRot());
            if (Math.abs(b2 - this.lastSentYHeadRot) >= 1) {
                this.synchronizer.sendToTrackingPlayers(new ClientboundRotateHeadPacket(this.entity, b2));
                this.lastSentYHeadRot = b2;
            }

            this.entity.needsSync = false;
        }

        this.tickCount++;
        if (this.entity.hurtMarked) {
            this.entity.hurtMarked = false;
            this.synchronizer.sendToTrackingPlayersAndSelf(new ClientboundSetEntityMotionPacket(this.entity));
        }
    }

    private void handleMinecartPosRot(NewMinecartBehavior behavior, byte yRot, byte xRot, boolean dirty) {
        this.sendDirtyEntityData();
        if (behavior.lerpSteps.isEmpty()) {
            Vec3 deltaMovement = this.entity.getDeltaMovement();
            double d = deltaMovement.distanceToSqr(this.lastSentMovement);
            Vec3 vec3 = this.entity.trackingPosition();
            boolean flag = this.positionCodec.delta(vec3).lengthSqr() >= 7.6293945E-6F;
            boolean flag1 = flag || this.tickCount % 60 == 0;
            if (flag1 || dirty || d > 1.0E-7) {
                this.synchronizer
                    .sendToTrackingPlayers(
                        new ClientboundMoveMinecartPacket(
                            this.entity.getId(),
                            List.of(
                                new NewMinecartBehavior.MinecartStep(
                                    this.entity.position(), this.entity.getDeltaMovement(), this.entity.getYRot(), this.entity.getXRot(), 1.0F
                                )
                            )
                        )
                    );
            }
        } else {
            this.synchronizer.sendToTrackingPlayers(new ClientboundMoveMinecartPacket(this.entity.getId(), List.copyOf(behavior.lerpSteps)));
            behavior.lerpSteps.clear();
        }

        this.lastSentYRot = yRot;
        this.lastSentXRot = xRot;
        this.positionCodec.setBase(this.entity.position());
    }

    public void removePairing(ServerPlayer player) {
        this.entity.stopSeenByPlayer(player);
        player.connection.send(new ClientboundRemoveEntitiesPacket(this.entity.getId()));
    }

    public void addPairing(ServerPlayer player) {
        List<Packet<? super ClientGamePacketListener>> list = new ArrayList<>();
        this.sendPairingData(player, list::add);
        player.connection.send(new ClientboundBundlePacket(list));
        this.entity.startSeenByPlayer(player);
    }

    public void sendPairingData(ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> consumer) {
        this.entity.updateDataBeforeSync();
        if (this.entity.isRemoved()) {
            LOGGER.warn("Fetching packet for removed entity {}", this.entity);
        }

        Packet<ClientGamePacketListener> addEntityPacket = this.entity.getAddEntityPacket(this);
        consumer.accept(addEntityPacket);
        if (this.trackedDataValues != null) {
            consumer.accept(new ClientboundSetEntityDataPacket(this.entity.getId(), this.trackedDataValues));
        }

        if (this.entity instanceof LivingEntity livingEntity) {
            Collection<AttributeInstance> syncableAttributes = livingEntity.getAttributes().getSyncableAttributes();
            if (!syncableAttributes.isEmpty()) {
                consumer.accept(new ClientboundUpdateAttributesPacket(this.entity.getId(), syncableAttributes));
            }
        }

        if (this.entity instanceof LivingEntity livingEntityx) {
            List<Pair<EquipmentSlot, ItemStack>> list = Lists.newArrayList();

            for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
                ItemStack itemBySlot = livingEntityx.getItemBySlot(equipmentSlot);
                if (!itemBySlot.isEmpty()) {
                    list.add(Pair.of(equipmentSlot, itemBySlot.copy()));
                }
            }

            if (!list.isEmpty()) {
                consumer.accept(new ClientboundSetEquipmentPacket(this.entity.getId(), list));
            }
        }

        if (!this.entity.getPassengers().isEmpty()) {
            consumer.accept(new ClientboundSetPassengersPacket(this.entity));
        }

        if (this.entity.isPassenger()) {
            consumer.accept(new ClientboundSetPassengersPacket(this.entity.getVehicle()));
        }

        if (this.entity instanceof Leashable leashable && leashable.isLeashed()) {
            consumer.accept(new ClientboundSetEntityLinkPacket(this.entity, leashable.getLeashHolder()));
        }
    }

    public Vec3 getPositionBase() {
        return this.positionCodec.getBase();
    }

    public Vec3 getLastSentMovement() {
        return this.lastSentMovement;
    }

    public float getLastSentXRot() {
        return Mth.unpackDegrees(this.lastSentXRot);
    }

    public float getLastSentYRot() {
        return Mth.unpackDegrees(this.lastSentYRot);
    }

    public float getLastSentYHeadRot() {
        return Mth.unpackDegrees(this.lastSentYHeadRot);
    }

    private void sendDirtyEntityData() {
        SynchedEntityData entityData = this.entity.getEntityData();
        List<SynchedEntityData.DataValue<?>> list = entityData.packDirty();
        if (list != null) {
            this.trackedDataValues = entityData.getNonDefaultValues();
            this.synchronizer.sendToTrackingPlayersAndSelf(new ClientboundSetEntityDataPacket(this.entity.getId(), list));
        }

        if (this.entity instanceof LivingEntity) {
            Set<AttributeInstance> attributesToSync = ((LivingEntity)this.entity).getAttributes().getAttributesToSync();
            if (!attributesToSync.isEmpty()) {
                this.synchronizer.sendToTrackingPlayersAndSelf(new ClientboundUpdateAttributesPacket(this.entity.getId(), attributesToSync));
            }

            attributesToSync.clear();
        }
    }

    public interface Synchronizer {
        void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet);

        void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet);

        void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> filter);
    }
}
