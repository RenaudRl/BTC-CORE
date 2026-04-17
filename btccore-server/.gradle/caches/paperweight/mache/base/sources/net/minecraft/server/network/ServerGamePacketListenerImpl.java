package net.minecraft.server.network;

import com.google.common.collect.Lists;
import com.google.common.primitives.Floats;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.HashedStack;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.LastSeenMessagesValidator;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MessageSignatureCache;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.ClientboundTestInstanceBlockStatus;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundChangeGameModePacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.FutureChain;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.SignatureValidator;
import net.minecraft.util.StringUtil;
import net.minecraft.util.TickThrottler;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.entity.TestBlockEntity;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ServerGamePacketListenerImpl
    extends ServerCommonPacketListenerImpl
    implements GameProtocols.Context,
    ServerGamePacketListener,
    ServerPlayerConnection,
    TickablePacketListener {
    static final Logger LOGGER = LogUtils.getLogger();
    private static final int NO_BLOCK_UPDATES_TO_ACK = -1;
    private static final int TRACKED_MESSAGE_DISCONNECT_THRESHOLD = 4096;
    private static final int MAXIMUM_FLYING_TICKS = 80;
    private static final int ATTACK_INDICATOR_TOLERANCE_TICKS = 5;
    public static final int CLIENT_LOADED_TIMEOUT_TIME = 60;
    private static final Component CHAT_VALIDATION_FAILED = Component.translatable("multiplayer.disconnect.chat_validation_failed");
    private static final Component INVALID_COMMAND_SIGNATURE = Component.translatable("chat.disabled.invalid_command_signature").withStyle(ChatFormatting.RED);
    private static final int MAX_COMMAND_SUGGESTIONS = 1000;
    public ServerPlayer player;
    public final PlayerChunkSender chunkSender;
    private int tickCount;
    private int ackBlockChangesUpTo = -1;
    private final TickThrottler chatSpamThrottler = new TickThrottler(20, 200);
    private final TickThrottler dropSpamThrottler = new TickThrottler(20, 1480);
    private double firstGoodX;
    private double firstGoodY;
    private double firstGoodZ;
    private double lastGoodX;
    private double lastGoodY;
    private double lastGoodZ;
    private @Nullable Entity lastVehicle;
    private double vehicleFirstGoodX;
    private double vehicleFirstGoodY;
    private double vehicleFirstGoodZ;
    private double vehicleLastGoodX;
    private double vehicleLastGoodY;
    private double vehicleLastGoodZ;
    private @Nullable Vec3 awaitingPositionFromClient;
    private int awaitingTeleport;
    private int awaitingTeleportTime;
    private boolean clientIsFloating;
    private int aboveGroundTickCount;
    private boolean clientVehicleIsFloating;
    private int aboveGroundVehicleTickCount;
    private int receivedMovePacketCount;
    private int knownMovePacketCount;
    private boolean receivedMovementThisTick;
    private @Nullable RemoteChatSession chatSession;
    private SignedMessageChain.Decoder signedMessageDecoder;
    private final LastSeenMessagesValidator lastSeenMessages = new LastSeenMessagesValidator(20);
    private int nextChatIndex;
    private final MessageSignatureCache messageSignatureCache = MessageSignatureCache.createDefault();
    private final FutureChain chatMessageChain;
    private boolean waitingForSwitchToConfig;
    private boolean waitingForRespawn;
    private int clientLoadedTimeoutTimer;

    public ServerGamePacketListenerImpl(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, cookie);
        this.restartClientLoadTimerAfterRespawn();
        this.chunkSender = new PlayerChunkSender(connection.isMemoryConnection());
        this.player = player;
        player.connection = this;
        player.getTextFilter().join();
        this.signedMessageDecoder = SignedMessageChain.Decoder.unsigned(player.getUUID(), server::enforceSecureProfile);
        this.chatMessageChain = new FutureChain(server);
    }

    @Override
    public void tick() {
        if (this.ackBlockChangesUpTo > -1) {
            this.send(new ClientboundBlockChangedAckPacket(this.ackBlockChangesUpTo));
            this.ackBlockChangesUpTo = -1;
        }

        if (this.server.isPaused() || !this.tickPlayer()) {
            this.keepConnectionAlive();
            this.chatSpamThrottler.tick();
            this.dropSpamThrottler.tick();
            if (this.player.getLastActionTime() > 0L
                && this.server.playerIdleTimeout() > 0
                && Util.getMillis() - this.player.getLastActionTime() > TimeUnit.MINUTES.toMillis(this.server.playerIdleTimeout())
                && !this.player.wonGame) {
                this.disconnect(Component.translatable("multiplayer.disconnect.idling"));
            }
        }
    }

    private boolean tickPlayer() {
        this.resetPosition();
        this.player.xo = this.player.getX();
        this.player.yo = this.player.getY();
        this.player.zo = this.player.getZ();
        this.player.doTick();
        this.player.absSnapTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, this.player.getYRot(), this.player.getXRot());
        this.tickCount++;
        this.knownMovePacketCount = this.receivedMovePacketCount;
        if (this.clientIsFloating && !this.player.isSleeping() && !this.player.isPassenger() && !this.player.isDeadOrDying()) {
            if (++this.aboveGroundTickCount > this.getMaximumFlyingTicks(this.player)) {
                LOGGER.warn("{} was kicked for floating too long!", this.player.getPlainTextName());
                this.disconnect(Component.translatable("multiplayer.disconnect.flying"));
                return true;
            }
        } else {
            this.clientIsFloating = false;
            this.aboveGroundTickCount = 0;
        }

        this.lastVehicle = this.player.getRootVehicle();
        if (this.lastVehicle != this.player && this.lastVehicle.getControllingPassenger() == this.player) {
            this.vehicleFirstGoodX = this.lastVehicle.getX();
            this.vehicleFirstGoodY = this.lastVehicle.getY();
            this.vehicleFirstGoodZ = this.lastVehicle.getZ();
            this.vehicleLastGoodX = this.lastVehicle.getX();
            this.vehicleLastGoodY = this.lastVehicle.getY();
            this.vehicleLastGoodZ = this.lastVehicle.getZ();
            if (this.clientVehicleIsFloating && this.lastVehicle.getControllingPassenger() == this.player) {
                if (++this.aboveGroundVehicleTickCount > this.getMaximumFlyingTicks(this.lastVehicle)) {
                    LOGGER.warn("{} was kicked for floating a vehicle too long!", this.player.getPlainTextName());
                    this.disconnect(Component.translatable("multiplayer.disconnect.flying"));
                    return true;
                }
            } else {
                this.clientVehicleIsFloating = false;
                this.aboveGroundVehicleTickCount = 0;
            }
        } else {
            this.lastVehicle = null;
            this.clientVehicleIsFloating = false;
            this.aboveGroundVehicleTickCount = 0;
        }

        return false;
    }

    private int getMaximumFlyingTicks(Entity entity) {
        double gravity = entity.getGravity();
        if (gravity < 1.0E-5F) {
            return Integer.MAX_VALUE;
        } else {
            double d = 0.08 / gravity;
            return Mth.ceil(80.0 * Math.max(d, 1.0));
        }
    }

    public void resetFlyingTicks() {
        this.aboveGroundTickCount = 0;
        this.aboveGroundVehicleTickCount = 0;
    }

    public void resetPosition() {
        this.firstGoodX = this.player.getX();
        this.firstGoodY = this.player.getY();
        this.firstGoodZ = this.player.getZ();
        this.lastGoodX = this.player.getX();
        this.lastGoodY = this.player.getY();
        this.lastGoodZ = this.player.getZ();
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected() && !this.waitingForSwitchToConfig;
    }

    @Override
    public boolean shouldHandleMessage(Packet<?> packet) {
        return super.shouldHandleMessage(packet)
            || this.waitingForSwitchToConfig && this.connection.isConnected() && packet instanceof ServerboundConfigurationAcknowledgedPacket;
    }

    @Override
    protected GameProfile playerProfile() {
        return this.player.getGameProfile();
    }

    private <T, R> CompletableFuture<R> filterTextPacket(T message, BiFunction<TextFilter, T, CompletableFuture<R>> processor) {
        return processor.apply(this.player.getTextFilter(), message).thenApply(result -> {
            if (!this.isAcceptingMessages()) {
                LOGGER.debug("Ignoring packet due to disconnection");
                throw new CancellationException("disconnected");
            } else {
                return (R)result;
            }
        });
    }

    private CompletableFuture<FilteredText> filterTextPacket(String text) {
        return this.filterTextPacket(text, TextFilter::processStreamMessage);
    }

    private CompletableFuture<List<FilteredText>> filterTextPacket(List<String> texts) {
        return this.filterTextPacket(texts, TextFilter::processMessageBundle);
    }

    @Override
    public void handlePlayerInput(ServerboundPlayerInputPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.setLastClientInput(packet.input());
        if (this.hasClientLoaded()) {
            this.player.resetLastActionTime();
            this.player.setShiftKeyDown(packet.input().shift());
        }
    }

    private static boolean containsInvalidValues(double x, double y, double z, float yRot, float xRot) {
        return Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) || !Floats.isFinite(xRot) || !Floats.isFinite(yRot);
    }

    private static double clampHorizontal(double value) {
        return Mth.clamp(value, -3.0E7, 3.0E7);
    }

    private static double clampVertical(double value) {
        return Mth.clamp(value, -2.0E7, 2.0E7);
    }

    @Override
    public void handleMoveVehicle(ServerboundMoveVehiclePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (containsInvalidValues(packet.position().x(), packet.position().y(), packet.position().z(), packet.yRot(), packet.xRot())) {
            this.disconnect(Component.translatable("multiplayer.disconnect.invalid_vehicle_movement"));
        } else if (!this.updateAwaitingTeleport() && this.hasClientLoaded()) {
            Entity rootVehicle = this.player.getRootVehicle();
            if (rootVehicle != this.player && rootVehicle.getControllingPassenger() == this.player && rootVehicle == this.lastVehicle) {
                ServerLevel serverLevel = this.player.level();
                double x = rootVehicle.getX();
                double y = rootVehicle.getY();
                double z = rootVehicle.getZ();
                double d = clampHorizontal(packet.position().x());
                double d1 = clampVertical(packet.position().y());
                double d2 = clampHorizontal(packet.position().z());
                float f = Mth.wrapDegrees(packet.yRot());
                float f1 = Mth.wrapDegrees(packet.xRot());
                double d3 = d - this.vehicleFirstGoodX;
                double d4 = d1 - this.vehicleFirstGoodY;
                double d5 = d2 - this.vehicleFirstGoodZ;
                double d6 = rootVehicle.getDeltaMovement().lengthSqr();
                double d7 = d3 * d3 + d4 * d4 + d5 * d5;
                if (d7 - d6 > 100.0 && !this.isSingleplayerOwner()) {
                    LOGGER.warn("{} (vehicle of {}) moved too quickly! {},{},{}", rootVehicle.getPlainTextName(), this.player.getPlainTextName(), d3, d4, d5);
                    this.send(ClientboundMoveVehiclePacket.fromEntity(rootVehicle));
                    return;
                }

                AABB boundingBox = rootVehicle.getBoundingBox();
                d3 = d - this.vehicleLastGoodX;
                d4 = d1 - this.vehicleLastGoodY;
                d5 = d2 - this.vehicleLastGoodZ;
                boolean flag = rootVehicle.verticalCollisionBelow;
                if (rootVehicle instanceof LivingEntity livingEntity && livingEntity.onClimbable()) {
                    livingEntity.resetFallDistance();
                }

                rootVehicle.move(MoverType.PLAYER, new Vec3(d3, d4, d5));
                double verticalDelta = d4;
                d3 = d - rootVehicle.getX();
                d4 = d1 - rootVehicle.getY();
                if (d4 > -0.5 || d4 < 0.5) {
                    d4 = 0.0;
                }

                d5 = d2 - rootVehicle.getZ();
                d7 = d3 * d3 + d4 * d4 + d5 * d5;
                boolean flag1 = false;
                if (d7 > 0.0625) {
                    flag1 = true;
                    LOGGER.warn("{} (vehicle of {}) moved wrongly! {}", rootVehicle.getPlainTextName(), this.player.getPlainTextName(), Math.sqrt(d7));
                }

                if (flag1 && serverLevel.noCollision(rootVehicle, boundingBox)
                    || this.isEntityCollidingWithAnythingNew(serverLevel, rootVehicle, boundingBox, d, d1, d2)) {
                    rootVehicle.absSnapTo(x, y, z, f, f1);
                    this.send(ClientboundMoveVehiclePacket.fromEntity(rootVehicle));
                    rootVehicle.removeLatestMovementRecording();
                    return;
                }

                rootVehicle.absSnapTo(d, d1, d2, f, f1);
                this.player.level().getChunkSource().move(this.player);
                Vec3 vec3 = new Vec3(rootVehicle.getX() - x, rootVehicle.getY() - y, rootVehicle.getZ() - z);
                this.handlePlayerKnownMovement(vec3);
                rootVehicle.setOnGroundWithMovement(packet.onGround(), vec3);
                rootVehicle.doCheckFallDamage(vec3.x, vec3.y, vec3.z, packet.onGround());
                this.player.checkMovementStatistics(vec3.x, vec3.y, vec3.z);
                this.clientVehicleIsFloating = verticalDelta >= -0.03125
                    && !flag
                    && !this.server.allowFlight()
                    && !rootVehicle.isFlyingVehicle()
                    && !rootVehicle.isNoGravity()
                    && this.noBlocksAround(rootVehicle);
                this.vehicleLastGoodX = rootVehicle.getX();
                this.vehicleLastGoodY = rootVehicle.getY();
                this.vehicleLastGoodZ = rootVehicle.getZ();
            }
        }
    }

    private boolean noBlocksAround(Entity entity) {
        return entity.level()
            .getBlockStates(entity.getBoundingBox().inflate(0.0625).expandTowards(0.0, -0.55, 0.0))
            .allMatch(BlockBehaviour.BlockStateBase::isAir);
    }

    @Override
    public void handleAcceptTeleportPacket(ServerboundAcceptTeleportationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (packet.getId() == this.awaitingTeleport) {
            if (this.awaitingPositionFromClient == null) {
                this.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"));
                return;
            }

            this.player
                .absSnapTo(
                    this.awaitingPositionFromClient.x,
                    this.awaitingPositionFromClient.y,
                    this.awaitingPositionFromClient.z,
                    this.player.getYRot(),
                    this.player.getXRot()
                );
            this.lastGoodX = this.awaitingPositionFromClient.x;
            this.lastGoodY = this.awaitingPositionFromClient.y;
            this.lastGoodZ = this.awaitingPositionFromClient.z;
            this.player.hasChangedDimension();
            this.awaitingPositionFromClient = null;
        }
    }

    @Override
    public void handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.markClientLoaded();
    }

    @Override
    public void handleRecipeBookSeenRecipePacket(ServerboundRecipeBookSeenRecipePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        RecipeManager.ServerDisplayInfo recipeFromDisplay = this.server.getRecipeManager().getRecipeFromDisplay(packet.recipe());
        if (recipeFromDisplay != null) {
            this.player.getRecipeBook().removeHighlight(recipeFromDisplay.parent().id());
        }
    }

    @Override
    public void handleBundleItemSelectedPacket(ServerboundSelectBundleItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.containerMenu.setSelectedBundleItemIndex(packet.slotId(), packet.selectedItemIndex());
    }

    @Override
    public void handleRecipeBookChangeSettingsPacket(ServerboundRecipeBookChangeSettingsPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.getRecipeBook().setBookSetting(packet.getBookType(), packet.isOpen(), packet.isFiltering());
    }

    @Override
    public void handleSeenAdvancements(ServerboundSeenAdvancementsPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (packet.getAction() == ServerboundSeenAdvancementsPacket.Action.OPENED_TAB) {
            Identifier identifier = Objects.requireNonNull(packet.getTab());
            AdvancementHolder advancementHolder = this.server.getAdvancements().get(identifier);
            if (advancementHolder != null) {
                this.player.getAdvancements().setSelectedTab(advancementHolder);
            }
        }
    }

    @Override
    public void handleCustomCommandSuggestions(ServerboundCommandSuggestionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        StringReader stringReader = new StringReader(packet.getCommand());
        if (stringReader.canRead() && stringReader.peek() == '/') {
            stringReader.skip();
        }

        ParseResults<CommandSourceStack> parseResults = this.server.getCommands().getDispatcher().parse(stringReader, this.player.createCommandSourceStack());
        this.server
            .getCommands()
            .getDispatcher()
            .getCompletionSuggestions(parseResults)
            .thenAccept(
                suggestions -> {
                    Suggestions suggestions1 = suggestions.getList().size() <= 1000
                        ? suggestions
                        : new Suggestions(suggestions.getRange(), suggestions.getList().subList(0, 1000));
                    this.send(new ClientboundCommandSuggestionsPacket(packet.getId(), suggestions1));
                }
            );
    }

    @Override
    public void handleSetCommandBlock(ServerboundSetCommandBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.canUseGameMasterBlocks()) {
            this.player.sendSystemMessage(Component.translatable("advMode.notAllowed"));
        } else {
            BaseCommandBlock baseCommandBlock = null;
            CommandBlockEntity commandBlockEntity = null;
            BlockPos pos = packet.getPos();
            BlockEntity blockEntity = this.player.level().getBlockEntity(pos);
            if (blockEntity instanceof CommandBlockEntity commandBlockEntity1) {
                commandBlockEntity = commandBlockEntity1;
                baseCommandBlock = commandBlockEntity1.getCommandBlock();
            }

            String command = packet.getCommand();
            boolean isTrackOutput = packet.isTrackOutput();
            if (baseCommandBlock != null) {
                CommandBlockEntity.Mode mode = commandBlockEntity.getMode();
                BlockState blockState = this.player.level().getBlockState(pos);
                Direction direction = blockState.getValue(CommandBlock.FACING);

                BlockState blockState1 = switch (packet.getMode()) {
                    case SEQUENCE -> Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState();
                    case AUTO -> Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState();
                    default -> Blocks.COMMAND_BLOCK.defaultBlockState();
                };
                BlockState blockState2 = blockState1.setValue(CommandBlock.FACING, direction).setValue(CommandBlock.CONDITIONAL, packet.isConditional());
                if (blockState2 != blockState) {
                    this.player.level().setBlock(pos, blockState2, Block.UPDATE_CLIENTS);
                    blockEntity.setBlockState(blockState2);
                    this.player.level().getChunkAt(pos).setBlockEntity(blockEntity);
                }

                baseCommandBlock.setCommand(command);
                baseCommandBlock.setTrackOutput(isTrackOutput);
                if (!isTrackOutput) {
                    baseCommandBlock.setLastOutput(null);
                }

                commandBlockEntity.setAutomatic(packet.isAutomatic());
                if (mode != packet.getMode()) {
                    commandBlockEntity.onModeSwitch();
                }

                if (this.player.level().isCommandBlockEnabled()) {
                    baseCommandBlock.onUpdated(this.player.level());
                }

                if (!StringUtil.isNullOrEmpty(command)) {
                    this.player
                        .sendSystemMessage(
                            Component.translatable(
                                this.player.level().isCommandBlockEnabled() ? "advMode.setCommand.success" : "advMode.setCommand.disabled", command
                            )
                        );
                }
            }
        }
    }

    @Override
    public void handleSetCommandMinecart(ServerboundSetCommandMinecartPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.canUseGameMasterBlocks()) {
            this.player.sendSystemMessage(Component.translatable("advMode.notAllowed"));
        } else {
            BaseCommandBlock commandBlock = packet.getCommandBlock(this.player.level());
            if (commandBlock != null) {
                String command = packet.getCommand();
                commandBlock.setCommand(command);
                commandBlock.setTrackOutput(packet.isTrackOutput());
                if (!packet.isTrackOutput()) {
                    commandBlock.setLastOutput(null);
                }

                boolean isCommandBlockEnabled = this.player.level().isCommandBlockEnabled();
                if (isCommandBlockEnabled) {
                    commandBlock.onUpdated(this.player.level());
                }

                if (!StringUtil.isNullOrEmpty(command)) {
                    this.player
                        .sendSystemMessage(
                            Component.translatable(isCommandBlockEnabled ? "advMode.setCommand.success" : "advMode.setCommand.disabled", command)
                        );
                }
            }
        }
    }

    @Override
    public void handlePickItemFromBlock(ServerboundPickItemFromBlockPacket packet) {
        ServerLevel serverLevel = this.player.level();
        PacketUtils.ensureRunningOnSameThread(packet, this, serverLevel);
        BlockPos blockPos = packet.pos();
        if (this.player.isWithinBlockInteractionRange(blockPos, 1.0)) {
            if (serverLevel.isLoaded(blockPos)) {
                BlockState blockState = serverLevel.getBlockState(blockPos);
                boolean flag = this.player.hasInfiniteMaterials() && packet.includeData();
                ItemStack cloneItemStack = blockState.getCloneItemStack(serverLevel, blockPos, flag);
                if (!cloneItemStack.isEmpty()) {
                    if (flag) {
                        addBlockDataToItem(blockState, serverLevel, blockPos, cloneItemStack);
                    }

                    this.tryPickItem(cloneItemStack);
                }
            }
        }
    }

    private static void addBlockDataToItem(BlockState state, ServerLevel level, BlockPos pos, ItemStack stack) {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        if (blockEntity != null) {
            try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), LOGGER)) {
                TagValueOutput tagValueOutput = TagValueOutput.createWithContext(scopedCollector, level.registryAccess());
                blockEntity.saveCustomOnly(tagValueOutput);
                blockEntity.removeComponentsFromTag(tagValueOutput);
                BlockItem.setBlockEntityData(stack, blockEntity.getType(), tagValueOutput);
                stack.applyComponents(blockEntity.collectComponents());
            }
        }
    }

    @Override
    public void handlePickItemFromEntity(ServerboundPickItemFromEntityPacket packet) {
        ServerLevel serverLevel = this.player.level();
        PacketUtils.ensureRunningOnSameThread(packet, this, serverLevel);
        Entity entityOrPart = serverLevel.getEntityOrPart(packet.id());
        if (entityOrPart != null && this.player.isWithinEntityInteractionRange(entityOrPart, 3.0)) {
            ItemStack pickResult = entityOrPart.getPickResult();
            if (pickResult != null && !pickResult.isEmpty()) {
                this.tryPickItem(pickResult);
            }
        }
    }

    private void tryPickItem(ItemStack stack) {
        if (stack.isItemEnabled(this.player.level().enabledFeatures())) {
            Inventory inventory = this.player.getInventory();
            int i = inventory.findSlotMatchingItem(stack);
            if (i != -1) {
                if (Inventory.isHotbarSlot(i)) {
                    inventory.setSelectedSlot(i);
                } else {
                    inventory.pickSlot(i);
                }
            } else if (this.player.hasInfiniteMaterials()) {
                inventory.addAndPickItem(stack);
            }

            this.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
            this.player.inventoryMenu.broadcastChanges();
        }
    }

    @Override
    public void handleRenameItem(ServerboundRenameItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.containerMenu instanceof AnvilMenu anvilMenu) {
            if (!anvilMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, anvilMenu);
                return;
            }

            anvilMenu.setItemName(packet.getName());
        }
    }

    @Override
    public void handleSetBeaconPacket(ServerboundSetBeaconPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.containerMenu instanceof BeaconMenu beaconMenu) {
            if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
                return;
            }

            beaconMenu.updateEffects(packet.primary(), packet.secondary());
        }
    }

    @Override
    public void handleSetStructureBlock(ServerboundSetStructureBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos pos = packet.getPos();
            BlockState blockState = this.player.level().getBlockState(pos);
            if (this.player.level().getBlockEntity(pos) instanceof StructureBlockEntity structureBlockEntity) {
                structureBlockEntity.setMode(packet.getMode());
                structureBlockEntity.setStructureName(packet.getName());
                structureBlockEntity.setStructurePos(packet.getOffset());
                structureBlockEntity.setStructureSize(packet.getSize());
                structureBlockEntity.setMirror(packet.getMirror());
                structureBlockEntity.setRotation(packet.getRotation());
                structureBlockEntity.setMetaData(packet.getData());
                structureBlockEntity.setIgnoreEntities(packet.isIgnoreEntities());
                structureBlockEntity.setStrict(packet.isStrict());
                structureBlockEntity.setShowAir(packet.isShowAir());
                structureBlockEntity.setShowBoundingBox(packet.isShowBoundingBox());
                structureBlockEntity.setIntegrity(packet.getIntegrity());
                structureBlockEntity.setSeed(packet.getSeed());
                if (structureBlockEntity.hasStructureName()) {
                    String structureName = structureBlockEntity.getStructureName();
                    if (packet.getUpdateType() == StructureBlockEntity.UpdateType.SAVE_AREA) {
                        if (structureBlockEntity.saveStructure()) {
                            this.player.displayClientMessage(Component.translatable("structure_block.save_success", structureName), false);
                        } else {
                            this.player.displayClientMessage(Component.translatable("structure_block.save_failure", structureName), false);
                        }
                    } else if (packet.getUpdateType() == StructureBlockEntity.UpdateType.LOAD_AREA) {
                        if (!structureBlockEntity.isStructureLoadable()) {
                            this.player.displayClientMessage(Component.translatable("structure_block.load_not_found", structureName), false);
                        } else if (structureBlockEntity.placeStructureIfSameSize(this.player.level())) {
                            this.player.displayClientMessage(Component.translatable("structure_block.load_success", structureName), false);
                        } else {
                            this.player.displayClientMessage(Component.translatable("structure_block.load_prepare", structureName), false);
                        }
                    } else if (packet.getUpdateType() == StructureBlockEntity.UpdateType.SCAN_AREA) {
                        if (structureBlockEntity.detectSize()) {
                            this.player.displayClientMessage(Component.translatable("structure_block.size_success", structureName), false);
                        } else {
                            this.player.displayClientMessage(Component.translatable("structure_block.size_failure"), false);
                        }
                    }
                } else {
                    this.player.displayClientMessage(Component.translatable("structure_block.invalid_structure_name", packet.getName()), false);
                }

                structureBlockEntity.setChanged();
                this.player.level().sendBlockUpdated(pos, blockState, blockState, Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public void handleSetTestBlock(ServerboundSetTestBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos blockPos = packet.position();
            BlockState blockState = this.player.level().getBlockState(blockPos);
            if (this.player.level().getBlockEntity(blockPos) instanceof TestBlockEntity testBlockEntity) {
                testBlockEntity.setMode(packet.mode());
                testBlockEntity.setMessage(packet.message());
                testBlockEntity.setChanged();
                this.player.level().sendBlockUpdated(blockPos, blockState, testBlockEntity.getBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public void handleTestInstanceBlockAction(ServerboundTestInstanceBlockActionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        BlockPos blockPos = packet.pos();
        if (this.player.canUseGameMasterBlocks() && this.player.level().getBlockEntity(blockPos) instanceof TestInstanceBlockEntity testInstanceBlockEntity) {
            if (packet.action() != ServerboundTestInstanceBlockActionPacket.Action.QUERY
                && packet.action() != ServerboundTestInstanceBlockActionPacket.Action.INIT) {
                testInstanceBlockEntity.set(packet.data());
                if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.RESET) {
                    testInstanceBlockEntity.resetTest(this.player::sendSystemMessage);
                } else if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.SAVE) {
                    testInstanceBlockEntity.saveTest(this.player::sendSystemMessage);
                } else if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.EXPORT) {
                    testInstanceBlockEntity.exportTest(this.player::sendSystemMessage);
                } else if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.RUN) {
                    testInstanceBlockEntity.runTest(this.player::sendSystemMessage);
                }

                BlockState blockState = this.player.level().getBlockState(blockPos);
                this.player.level().sendBlockUpdated(blockPos, Blocks.AIR.defaultBlockState(), blockState, Block.UPDATE_ALL);
            } else {
                Registry<GameTestInstance> registry = this.player.registryAccess().lookupOrThrow(Registries.TEST_INSTANCE);
                Optional<Holder.Reference<GameTestInstance>> optional = packet.data().test().flatMap(registry::get);
                Component component;
                if (optional.isPresent()) {
                    component = optional.get().value().describe();
                } else {
                    component = Component.translatable("test_instance.description.no_test").withStyle(ChatFormatting.RED);
                }

                Optional<Vec3i> optional1;
                if (packet.action() == ServerboundTestInstanceBlockActionPacket.Action.QUERY) {
                    optional1 = packet.data()
                        .test()
                        .flatMap(resourceKey -> TestInstanceBlockEntity.getStructureSize(this.player.level(), (ResourceKey<GameTestInstance>)resourceKey));
                } else {
                    optional1 = Optional.empty();
                }

                this.connection.send(new ClientboundTestInstanceBlockStatus(component, optional1));
            }
        }
    }

    @Override
    public void handleSetJigsawBlock(ServerboundSetJigsawBlockPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos pos = packet.getPos();
            BlockState blockState = this.player.level().getBlockState(pos);
            if (this.player.level().getBlockEntity(pos) instanceof JigsawBlockEntity jigsawBlockEntity) {
                jigsawBlockEntity.setName(packet.getName());
                jigsawBlockEntity.setTarget(packet.getTarget());
                jigsawBlockEntity.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, packet.getPool()));
                jigsawBlockEntity.setFinalState(packet.getFinalState());
                jigsawBlockEntity.setJoint(packet.getJoint());
                jigsawBlockEntity.setPlacementPriority(packet.getPlacementPriority());
                jigsawBlockEntity.setSelectionPriority(packet.getSelectionPriority());
                jigsawBlockEntity.setChanged();
                this.player.level().sendBlockUpdated(pos, blockState, blockState, Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public void handleJigsawGenerate(ServerboundJigsawGeneratePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.canUseGameMasterBlocks()) {
            BlockPos pos = packet.getPos();
            if (this.player.level().getBlockEntity(pos) instanceof JigsawBlockEntity jigsawBlockEntity) {
                jigsawBlockEntity.generate(this.player.level(), packet.levels(), packet.keepJigsaws());
            }
        }
    }

    @Override
    public void handleSelectTrade(ServerboundSelectTradePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        int item = packet.getItem();
        if (this.player.containerMenu instanceof MerchantMenu merchantMenu) {
            if (!merchantMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, merchantMenu);
                return;
            }

            merchantMenu.setSelectionHint(item);
            merchantMenu.tryMoveItems(item);
        }
    }

    @Override
    public void handleEditBook(ServerboundEditBookPacket packet) {
        int slot = packet.slot();
        if (Inventory.isHotbarSlot(slot) || slot == 40) {
            List<String> list = Lists.newArrayList();
            Optional<String> optional = packet.title();
            optional.ifPresent(list::add);
            list.addAll(packet.pages());
            Consumer<List<FilteredText>> consumer = optional.isPresent()
                ? texts -> this.signBook(texts.get(0), texts.subList(1, texts.size()), slot)
                : list1 -> this.updateBookContents(list1, slot);
            this.filterTextPacket(list).thenAcceptAsync(consumer, this.server);
        }
    }

    private void updateBookContents(List<FilteredText> pages, int index) {
        ItemStack item = this.player.getInventory().getItem(index);
        if (item.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
            List<Filterable<String>> list = pages.stream().map(this::filterableFromOutgoing).toList();
            item.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(list));
        }
    }

    private void signBook(FilteredText title, List<FilteredText> pages, int index) {
        ItemStack item = this.player.getInventory().getItem(index);
        if (item.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
            ItemStack itemStack = item.transmuteCopy(Items.WRITTEN_BOOK);
            itemStack.remove(DataComponents.WRITABLE_BOOK_CONTENT);
            List<Filterable<Component>> list = pages.stream().map(filteredText -> this.filterableFromOutgoing(filteredText).<Component>map(Component::literal)).toList();
            itemStack.set(
                DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(this.filterableFromOutgoing(title), this.player.getPlainTextName(), 0, list, true)
            );
            this.player.getInventory().setItem(index, itemStack);
        }
    }

    private Filterable<String> filterableFromOutgoing(FilteredText filteredText) {
        return this.player.isTextFilteringEnabled() ? Filterable.passThrough(filteredText.filteredOrEmpty()) : Filterable.from(filteredText);
    }

    @Override
    public void handleEntityTagQuery(ServerboundEntityTagQueryPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            Entity entity = this.player.level().getEntity(packet.getEntityId());
            if (entity != null) {
                try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
                    TagValueOutput tagValueOutput = TagValueOutput.createWithContext(scopedCollector, entity.registryAccess());
                    entity.saveWithoutId(tagValueOutput);
                    CompoundTag compoundTag = tagValueOutput.buildResult();
                    this.send(new ClientboundTagQueryPacket(packet.getTransactionId(), compoundTag));
                }
            }
        }
    }

    @Override
    public void handleContainerSlotStateChanged(ServerboundContainerSlotStateChangedPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.isSpectator() && packet.containerId() == this.player.containerMenu.containerId) {
            if (this.player.containerMenu instanceof CrafterMenu crafterMenu && crafterMenu.getContainer() instanceof CrafterBlockEntity crafterBlockEntity) {
                crafterBlockEntity.setSlotState(packet.slotId(), packet.newState());
            }
        }
    }

    @Override
    public void handleBlockEntityTagQuery(ServerboundBlockEntityTagQueryPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            BlockEntity blockEntity = this.player.level().getBlockEntity(packet.getPos());
            CompoundTag compoundTag = blockEntity != null ? blockEntity.saveWithoutMetadata(this.player.registryAccess()) : null;
            this.send(new ClientboundTagQueryPacket(packet.getTransactionId(), compoundTag));
        }
    }

    @Override
    public void handleMovePlayer(ServerboundMovePlayerPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (containsInvalidValues(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0), packet.getYRot(0.0F), packet.getXRot(0.0F))) {
            this.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"));
        } else {
            ServerLevel serverLevel = this.player.level();
            if (!this.player.wonGame) {
                if (this.tickCount == 0) {
                    this.resetPosition();
                }

                if (this.hasClientLoaded()) {
                    float f = Mth.wrapDegrees(packet.getYRot(this.player.getYRot()));
                    float f1 = Mth.wrapDegrees(packet.getXRot(this.player.getXRot()));
                    if (this.updateAwaitingTeleport()) {
                        this.player.absSnapRotationTo(f, f1);
                    } else {
                        double d = clampHorizontal(packet.getX(this.player.getX()));
                        double d1 = clampVertical(packet.getY(this.player.getY()));
                        double d2 = clampHorizontal(packet.getZ(this.player.getZ()));
                        if (this.player.isPassenger()) {
                            this.player.absSnapTo(this.player.getX(), this.player.getY(), this.player.getZ(), f, f1);
                            this.player.level().getChunkSource().move(this.player);
                        } else {
                            double x = this.player.getX();
                            double y = this.player.getY();
                            double z = this.player.getZ();
                            double d3 = d - this.firstGoodX;
                            double d4 = d1 - this.firstGoodY;
                            double d5 = d2 - this.firstGoodZ;
                            double d6 = this.player.getDeltaMovement().lengthSqr();
                            double d7 = d3 * d3 + d4 * d4 + d5 * d5;
                            if (this.player.isSleeping()) {
                                if (d7 > 1.0) {
                                    this.teleport(this.player.getX(), this.player.getY(), this.player.getZ(), f, f1);
                                }
                            } else {
                                boolean isFallFlying = this.player.isFallFlying();
                                if (serverLevel.tickRateManager().runsNormally()) {
                                    this.receivedMovePacketCount++;
                                    int i = this.receivedMovePacketCount - this.knownMovePacketCount;
                                    if (i > 5) {
                                        LOGGER.debug(
                                            "{} is sending move packets too frequently ({} packets since last tick)", this.player.getPlainTextName(), i
                                        );
                                        i = 1;
                                    }

                                    if (this.shouldCheckPlayerMovement(isFallFlying)) {
                                        float f2 = isFallFlying ? 300.0F : 100.0F;
                                        if (d7 - d6 > f2 * i) {
                                            LOGGER.warn("{} moved too quickly! {},{},{}", this.player.getPlainTextName(), d3, d4, d5);
                                            this.teleport(
                                                this.player.getX(), this.player.getY(), this.player.getZ(), this.player.getYRot(), this.player.getXRot()
                                            );
                                            return;
                                        }
                                    }
                                }

                                AABB boundingBox = this.player.getBoundingBox();
                                d3 = d - this.lastGoodX;
                                d4 = d1 - this.lastGoodY;
                                d5 = d2 - this.lastGoodZ;
                                boolean flag = d4 > 0.0;
                                if (this.player.onGround() && !packet.isOnGround() && flag) {
                                    this.player.jumpFromGround();
                                }

                                boolean flag1 = this.player.verticalCollisionBelow;
                                this.player.move(MoverType.PLAYER, new Vec3(d3, d4, d5));
                                double verticalDelta = d4;
                                d3 = d - this.player.getX();
                                d4 = d1 - this.player.getY();
                                if (d4 > -0.5 || d4 < 0.5) {
                                    d4 = 0.0;
                                }

                                d5 = d2 - this.player.getZ();
                                d7 = d3 * d3 + d4 * d4 + d5 * d5;
                                boolean flag2 = false;
                                if (!this.player.isChangingDimension()
                                    && d7 > 0.0625
                                    && !this.player.isSleeping()
                                    && !this.player.isCreative()
                                    && !this.player.isSpectator()
                                    && !this.player.isInPostImpulseGraceTime()) {
                                    flag2 = true;
                                    LOGGER.warn("{} moved wrongly!", this.player.getPlainTextName());
                                }

                                if (this.player.noPhysics
                                    || this.player.isSleeping()
                                    || (!flag2 || !serverLevel.noCollision(this.player, boundingBox))
                                        && !this.isEntityCollidingWithAnythingNew(serverLevel, this.player, boundingBox, d, d1, d2)) {
                                    this.player.absSnapTo(d, d1, d2, f, f1);
                                    boolean isAutoSpinAttack = this.player.isAutoSpinAttack();
                                    this.clientIsFloating = verticalDelta >= -0.03125
                                        && !flag1
                                        && !this.player.isSpectator()
                                        && !this.server.allowFlight()
                                        && !this.player.getAbilities().mayfly
                                        && !this.player.hasEffect(MobEffects.LEVITATION)
                                        && !isFallFlying
                                        && !isAutoSpinAttack
                                        && this.noBlocksAround(this.player);
                                    this.player.level().getChunkSource().move(this.player);
                                    Vec3 vec3 = new Vec3(this.player.getX() - x, this.player.getY() - y, this.player.getZ() - z);
                                    this.player.setOnGroundWithMovement(packet.isOnGround(), packet.horizontalCollision(), vec3);
                                    this.player.doCheckFallDamage(vec3.x, vec3.y, vec3.z, packet.isOnGround());
                                    this.handlePlayerKnownMovement(vec3);
                                    if (flag) {
                                        this.player.resetFallDistance();
                                    }

                                    if (packet.isOnGround()
                                        || this.player.hasLandedInLiquid()
                                        || this.player.onClimbable()
                                        || this.player.isSpectator()
                                        || isFallFlying
                                        || isAutoSpinAttack) {
                                        this.player.tryResetCurrentImpulseContext();
                                    }

                                    this.player.checkMovementStatistics(this.player.getX() - x, this.player.getY() - y, this.player.getZ() - z);
                                    this.lastGoodX = this.player.getX();
                                    this.lastGoodY = this.player.getY();
                                    this.lastGoodZ = this.player.getZ();
                                } else {
                                    this.teleport(x, y, z, f, f1);
                                    this.player.doCheckFallDamage(this.player.getX() - x, this.player.getY() - y, this.player.getZ() - z, packet.isOnGround());
                                    this.player.removeLatestMovementRecording();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean shouldCheckPlayerMovement(boolean isElytraMovement) {
        if (this.isSingleplayerOwner()) {
            return false;
        } else if (this.player.isChangingDimension()) {
            return false;
        } else {
            GameRules gameRules = this.player.level().getGameRules();
            return gameRules.get(GameRules.PLAYER_MOVEMENT_CHECK) && (!isElytraMovement || gameRules.get(GameRules.ELYTRA_MOVEMENT_CHECK));
        }
    }

    private boolean updateAwaitingTeleport() {
        if (this.awaitingPositionFromClient != null) {
            if (this.tickCount - this.awaitingTeleportTime > 20) {
                this.awaitingTeleportTime = this.tickCount;
                this.teleport(
                    this.awaitingPositionFromClient.x,
                    this.awaitingPositionFromClient.y,
                    this.awaitingPositionFromClient.z,
                    this.player.getYRot(),
                    this.player.getXRot()
                );
            }

            return true;
        } else {
            this.awaitingTeleportTime = this.tickCount;
            return false;
        }
    }

    private boolean isEntityCollidingWithAnythingNew(LevelReader level, Entity entity, AABB box, double x, double y, double z) {
        AABB aabb = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
        Iterable<VoxelShape> preMoveCollisions = level.getPreMoveCollisions(entity, aabb.deflate(1.0E-5F), box.getBottomCenter());
        VoxelShape voxelShape = Shapes.create(box.deflate(1.0E-5F));

        for (VoxelShape voxelShape1 : preMoveCollisions) {
            if (!Shapes.joinIsNotEmpty(voxelShape1, voxelShape, BooleanOp.AND)) {
                return true;
            }
        }

        return false;
    }

    public void teleport(double x, double y, double z, float yRot, float xRot) {
        this.teleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Collections.emptySet());
    }

    public void teleport(PositionMoveRotation posMoveRotation, Set<Relative> relatives) {
        this.awaitingTeleportTime = this.tickCount;
        if (++this.awaitingTeleport == Integer.MAX_VALUE) {
            this.awaitingTeleport = 0;
        }

        this.player.teleportSetPosition(posMoveRotation, relatives);
        this.awaitingPositionFromClient = this.player.position();
        this.send(ClientboundPlayerPositionPacket.of(this.awaitingTeleport, posMoveRotation, relatives));
    }

    @Override
    public void handlePlayerAction(ServerboundPlayerActionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.hasClientLoaded()) {
            BlockPos pos = packet.getPos();
            this.player.resetLastActionTime();
            ServerboundPlayerActionPacket.Action action = packet.getAction();
            switch (action) {
                case STAB:
                    if (this.player.isSpectator()) {
                        return;
                    } else {
                        ItemStack itemInHand = this.player.getItemInHand(InteractionHand.MAIN_HAND);
                        if (this.player.cannotAttackWithItem(itemInHand, 5)) {
                            return;
                        }

                        PiercingWeapon piercingWeapon = itemInHand.get(DataComponents.PIERCING_WEAPON);
                        if (piercingWeapon != null) {
                            piercingWeapon.attack(this.player, EquipmentSlot.MAINHAND);
                        }

                        return;
                    }
                case SWAP_ITEM_WITH_OFFHAND:
                    if (!this.player.isSpectator()) {
                        ItemStack itemInHand1 = this.player.getItemInHand(InteractionHand.OFF_HAND);
                        this.player.setItemInHand(InteractionHand.OFF_HAND, this.player.getItemInHand(InteractionHand.MAIN_HAND));
                        this.player.setItemInHand(InteractionHand.MAIN_HAND, itemInHand1);
                        this.player.stopUsingItem();
                    }

                    return;
                case DROP_ITEM:
                    if (!this.player.isSpectator()) {
                        this.player.drop(false);
                    }

                    return;
                case DROP_ALL_ITEMS:
                    if (!this.player.isSpectator()) {
                        this.player.drop(true);
                    }

                    return;
                case RELEASE_USE_ITEM:
                    this.player.releaseUsingItem();
                    return;
                case START_DESTROY_BLOCK:
                case ABORT_DESTROY_BLOCK:
                case STOP_DESTROY_BLOCK:
                    this.player.gameMode.handleBlockBreakAction(pos, action, packet.getDirection(), this.player.level().getMaxY(), packet.getSequence());
                    this.ackBlockChangesUpTo(packet.getSequence());
                    return;
                default:
                    throw new IllegalArgumentException("Invalid player action");
            }
        }
    }

    private static boolean wasBlockPlacementAttempt(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        } else {
            Item item = stack.getItem();
            return (item instanceof BlockItem || item instanceof BucketItem bucketItem && bucketItem.getContent() != Fluids.EMPTY)
                && !player.getCooldowns().isOnCooldown(stack);
        }
    }

    @Override
    public void handleUseItemOn(ServerboundUseItemOnPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.hasClientLoaded()) {
            this.ackBlockChangesUpTo(packet.getSequence());
            ServerLevel serverLevel = this.player.level();
            InteractionHand hand = packet.getHand();
            ItemStack itemInHand = this.player.getItemInHand(hand);
            if (itemInHand.isItemEnabled(serverLevel.enabledFeatures())) {
                BlockHitResult hitResult = packet.getHitResult();
                Vec3 location = hitResult.getLocation();
                BlockPos blockPos = hitResult.getBlockPos();
                if (this.player.isWithinBlockInteractionRange(blockPos, 1.0)) {
                    Vec3 vec3 = location.subtract(Vec3.atCenterOf(blockPos));
                    double d = 1.0000001;
                    if (Math.abs(vec3.x()) < 1.0000001 && Math.abs(vec3.y()) < 1.0000001 && Math.abs(vec3.z()) < 1.0000001) {
                        Direction direction = hitResult.getDirection();
                        this.player.resetLastActionTime();
                        int maxY = this.player.level().getMaxY();
                        if (blockPos.getY() <= maxY) {
                            if (this.awaitingPositionFromClient == null && serverLevel.mayInteract(this.player, blockPos)) {
                                InteractionResult interactionResult = this.player.gameMode.useItemOn(this.player, serverLevel, itemInHand, hand, hitResult);
                                if (interactionResult.consumesAction()) {
                                    CriteriaTriggers.ANY_BLOCK_USE.trigger(this.player, hitResult.getBlockPos(), itemInHand.copy());
                                }

                                if (direction == Direction.UP
                                    && !interactionResult.consumesAction()
                                    && blockPos.getY() >= maxY
                                    && wasBlockPlacementAttempt(this.player, itemInHand)) {
                                    Component component = Component.translatable("build.tooHigh", maxY).withStyle(ChatFormatting.RED);
                                    this.player.sendSystemMessage(component, true);
                                } else if (interactionResult instanceof InteractionResult.Success success
                                    && success.swingSource() == InteractionResult.SwingSource.SERVER) {
                                    this.player.swing(hand, true);
                                }
                            }
                        } else {
                            Component component1 = Component.translatable("build.tooHigh", maxY).withStyle(ChatFormatting.RED);
                            this.player.sendSystemMessage(component1, true);
                        }

                        this.send(new ClientboundBlockUpdatePacket(serverLevel, blockPos));
                        this.send(new ClientboundBlockUpdatePacket(serverLevel, blockPos.relative(direction)));
                    } else {
                        LOGGER.warn(
                            "Rejecting UseItemOnPacket from {}: Location {} too far away from hit block {}.",
                            this.player.getGameProfile().name(),
                            location,
                            blockPos
                        );
                    }
                }
            }
        }
    }

    @Override
    public void handleUseItem(ServerboundUseItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.hasClientLoaded()) {
            this.ackBlockChangesUpTo(packet.getSequence());
            ServerLevel serverLevel = this.player.level();
            InteractionHand hand = packet.getHand();
            ItemStack itemInHand = this.player.getItemInHand(hand);
            this.player.resetLastActionTime();
            if (!itemInHand.isEmpty() && itemInHand.isItemEnabled(serverLevel.enabledFeatures())) {
                float f = Mth.wrapDegrees(packet.getYRot());
                float f1 = Mth.wrapDegrees(packet.getXRot());
                if (f1 != this.player.getXRot() || f != this.player.getYRot()) {
                    this.player.absSnapRotationTo(f, f1);
                }

                if (this.player.gameMode.useItem(this.player, serverLevel, itemInHand, hand) instanceof InteractionResult.Success success
                    && success.swingSource() == InteractionResult.SwingSource.SERVER) {
                    this.player.swing(hand, true);
                }
            }
        }
    }

    @Override
    public void handleTeleportToEntityPacket(ServerboundTeleportToEntityPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.isSpectator()) {
            for (ServerLevel serverLevel : this.server.getAllLevels()) {
                Entity entity = packet.getEntity(serverLevel);
                if (entity != null) {
                    this.player.teleportTo(serverLevel, entity.getX(), entity.getY(), entity.getZ(), Set.of(), entity.getYRot(), entity.getXRot(), true);
                    return;
                }
            }
        }
    }

    @Override
    public void handlePaddleBoat(ServerboundPaddleBoatPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.getControlledVehicle() instanceof AbstractBoat abstractBoat) {
            abstractBoat.setPaddleState(packet.getLeft(), packet.getRight());
        }
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        LOGGER.info("{} lost connection: {}", this.player.getPlainTextName(), details.reason().getString());
        this.removePlayerFromWorld();
        super.onDisconnect(details);
    }

    private void removePlayerFromWorld() {
        this.chatMessageChain.close();
        this.server.invalidateStatus();
        this.server
            .getPlayerList()
            .broadcastSystemMessage(Component.translatable("multiplayer.player.left", this.player.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
        this.player.disconnect();
        this.server.getPlayerList().remove(this.player);
        this.player.getTextFilter().leave();
    }

    public void ackBlockChangesUpTo(int sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("Expected packet sequence nr >= 0");
        } else {
            this.ackBlockChangesUpTo = Math.max(sequence, this.ackBlockChangesUpTo);
        }
    }

    @Override
    public void handleSetCarriedItem(ServerboundSetCarriedItemPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (packet.getSlot() >= 0 && packet.getSlot() < Inventory.getSelectionSize()) {
            if (this.player.getInventory().getSelectedSlot() != packet.getSlot() && this.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                this.player.stopUsingItem();
            }

            this.player.getInventory().setSelectedSlot(packet.getSlot());
            this.player.resetLastActionTime();
        } else {
            LOGGER.warn("{} tried to set an invalid carried item", this.player.getPlainTextName());
        }
    }

    @Override
    public void handleChat(ServerboundChatPacket packet) {
        Optional<LastSeenMessages> optional = this.unpackAndApplyLastSeen(packet.lastSeenMessages());
        if (!optional.isEmpty()) {
            this.tryHandleChat(packet.message(), false, () -> {
                PlayerChatMessage signedMessage;
                try {
                    signedMessage = this.getSignedMessage(packet, optional.get());
                } catch (SignedMessageChain.DecodeException var6) {
                    this.handleMessageDecodeFailure(var6);
                    return;
                }

                CompletableFuture<FilteredText> completableFuture = this.filterTextPacket(signedMessage.signedContent());
                Component component = this.server.getChatDecorator().decorate(this.player, signedMessage.decoratedContent());
                this.chatMessageChain.append(completableFuture, filteredText -> {
                    PlayerChatMessage playerChatMessage = signedMessage.withUnsignedContent(component).filter(filteredText.mask());
                    this.broadcastChatMessage(playerChatMessage);
                });
            });
        }
    }

    @Override
    public void handleChatCommand(ServerboundChatCommandPacket packet) {
        this.tryHandleChat(packet.command(), true, () -> {
            this.performUnsignedChatCommand(packet.command());
            this.detectRateSpam();
        });
    }

    private void performUnsignedChatCommand(String command) {
        ParseResults<CommandSourceStack> parseResults = this.parseCommand(command);
        if (this.server.enforceSecureProfile() && SignableCommand.hasSignableArguments(parseResults)) {
            LOGGER.error(
                "Received unsigned command packet from {}, but the command requires signable arguments: {}", this.player.getGameProfile().name(), command
            );
            this.player.sendSystemMessage(INVALID_COMMAND_SIGNATURE);
        } else {
            this.server.getCommands().performCommand(parseResults, command);
        }
    }

    @Override
    public void handleSignedChatCommand(ServerboundChatCommandSignedPacket packet) {
        Optional<LastSeenMessages> optional = this.unpackAndApplyLastSeen(packet.lastSeenMessages());
        if (!optional.isEmpty()) {
            this.tryHandleChat(packet.command(), true, () -> {
                this.performSignedChatCommand(packet, optional.get());
                this.detectRateSpam();
            });
        }
    }

    private void performSignedChatCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeenMessages) {
        ParseResults<CommandSourceStack> parseResults = this.parseCommand(packet.command());

        Map<String, PlayerChatMessage> map;
        try {
            map = this.collectSignedArguments(packet, SignableCommand.of(parseResults), lastSeenMessages);
        } catch (SignedMessageChain.DecodeException var6) {
            this.handleMessageDecodeFailure(var6);
            return;
        }

        CommandSigningContext commandSigningContext = new CommandSigningContext.SignedArguments(map);
        parseResults = Commands.mapSource(
            parseResults, commandSourceStack -> commandSourceStack.withSigningContext(commandSigningContext, this.chatMessageChain)
        );
        this.server.getCommands().performCommand(parseResults, packet.command());
    }

    private void handleMessageDecodeFailure(SignedMessageChain.DecodeException exception) {
        LOGGER.warn("Failed to update secure chat state for {}: '{}'", this.player.getGameProfile().name(), exception.getComponent().getString());
        this.player.sendSystemMessage(exception.getComponent().copy().withStyle(ChatFormatting.RED));
    }

    private <S> Map<String, PlayerChatMessage> collectSignedArguments(
        ServerboundChatCommandSignedPacket packet, SignableCommand<S> command, LastSeenMessages lastSeenMessages
    ) throws SignedMessageChain.DecodeException {
        List<ArgumentSignatures.Entry> list = packet.argumentSignatures().entries();
        List<SignableCommand.Argument<S>> list1 = command.arguments();
        if (list.isEmpty()) {
            return this.collectUnsignedArguments(list1);
        } else {
            Map<String, PlayerChatMessage> map = new Object2ObjectOpenHashMap<>();

            for (ArgumentSignatures.Entry entry : list) {
                SignableCommand.Argument<S> argument = command.getArgument(entry.name());
                if (argument == null) {
                    this.signedMessageDecoder.setChainBroken();
                    throw createSignedArgumentMismatchException(packet.command(), list, list1);
                }

                SignedMessageBody signedMessageBody = new SignedMessageBody(argument.value(), packet.timeStamp(), packet.salt(), lastSeenMessages);
                map.put(argument.name(), this.signedMessageDecoder.unpack(entry.signature(), signedMessageBody));
            }

            for (SignableCommand.Argument<S> argument1 : list1) {
                if (!map.containsKey(argument1.name())) {
                    throw createSignedArgumentMismatchException(packet.command(), list, list1);
                }
            }

            return map;
        }
    }

    private <S> Map<String, PlayerChatMessage> collectUnsignedArguments(List<SignableCommand.Argument<S>> arguments) throws SignedMessageChain.DecodeException {
        Map<String, PlayerChatMessage> map = new HashMap<>();

        for (SignableCommand.Argument<S> argument : arguments) {
            SignedMessageBody signedMessageBody = SignedMessageBody.unsigned(argument.value());
            map.put(argument.name(), this.signedMessageDecoder.unpack(null, signedMessageBody));
        }

        return map;
    }

    private static <S> SignedMessageChain.DecodeException createSignedArgumentMismatchException(
        String command, List<ArgumentSignatures.Entry> signedArguments, List<SignableCommand.Argument<S>> unsignedArguments
    ) {
        String string = signedArguments.stream().map(ArgumentSignatures.Entry::name).collect(Collectors.joining(", "));
        String string1 = unsignedArguments.stream().map(SignableCommand.Argument::name).collect(Collectors.joining(", "));
        LOGGER.error("Signed command mismatch between server and client ('{}'): got [{}] from client, but expected [{}]", command, string, string1);
        return new SignedMessageChain.DecodeException(INVALID_COMMAND_SIGNATURE);
    }

    private ParseResults<CommandSourceStack> parseCommand(String command) {
        CommandDispatcher<CommandSourceStack> dispatcher = this.server.getCommands().getDispatcher();
        return dispatcher.parse(command, this.player.createCommandSourceStack());
    }

    private void tryHandleChat(String message, boolean bypassHiddenChat, Runnable handler) {
        if (isChatMessageIllegal(message)) {
            this.disconnect(Component.translatable("multiplayer.disconnect.illegal_characters"));
        } else if (!bypassHiddenChat && this.player.getChatVisibility() == ChatVisiblity.HIDDEN) {
            this.send(new ClientboundSystemChatPacket(Component.translatable("chat.disabled.options").withStyle(ChatFormatting.RED), false));
        } else {
            this.player.resetLastActionTime();
            this.server.execute(handler);
        }
    }

    private Optional<LastSeenMessages> unpackAndApplyLastSeen(LastSeenMessages.Update update) {
        synchronized (this.lastSeenMessages) {
            Optional var10000;
            try {
                LastSeenMessages lastSeenMessages = this.lastSeenMessages.applyUpdate(update);
                var10000 = Optional.of(lastSeenMessages);
            } catch (LastSeenMessagesValidator.ValidationException var5) {
                LOGGER.error("Failed to validate message acknowledgements from {}: {}", this.player.getPlainTextName(), var5.getMessage());
                this.disconnect(CHAT_VALIDATION_FAILED);
                return Optional.empty();
            }

            return var10000;
        }
    }

    public static boolean isChatMessageIllegal(String message) {
        for (int i = 0; i < message.length(); i++) {
            if (!StringUtil.isAllowedChatCharacter(message.charAt(i))) {
                return true;
            }
        }

        return false;
    }

    private PlayerChatMessage getSignedMessage(ServerboundChatPacket packet, LastSeenMessages lastSeenMessages) throws SignedMessageChain.DecodeException {
        SignedMessageBody signedMessageBody = new SignedMessageBody(packet.message(), packet.timeStamp(), packet.salt(), lastSeenMessages);
        return this.signedMessageDecoder.unpack(packet.signature(), signedMessageBody);
    }

    private void broadcastChatMessage(PlayerChatMessage message) {
        this.server.getPlayerList().broadcastChatMessage(message, this.player, ChatType.bind(ChatType.CHAT, this.player));
        this.detectRateSpam();
    }

    private void detectRateSpam() {
        this.chatSpamThrottler.increment();
        if (!this.chatSpamThrottler.isUnderThreshold()
            && !this.server.getPlayerList().isOp(this.player.nameAndId())
            && !this.server.isSingleplayerOwner(this.player.nameAndId())) {
            this.disconnect(Component.translatable("disconnect.spam"));
        }
    }

    @Override
    public void handleChatAck(ServerboundChatAckPacket packet) {
        synchronized (this.lastSeenMessages) {
            try {
                this.lastSeenMessages.applyOffset(packet.offset());
            } catch (LastSeenMessagesValidator.ValidationException var5) {
                LOGGER.error("Failed to validate message acknowledgement offset from {}: {}", this.player.getPlainTextName(), var5.getMessage());
                this.disconnect(CHAT_VALIDATION_FAILED);
            }
        }
    }

    @Override
    public void handleAnimate(ServerboundSwingPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.resetLastActionTime();
        this.player.swing(packet.getHand());
    }

    @Override
    public void handlePlayerCommand(ServerboundPlayerCommandPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.hasClientLoaded()) {
            this.player.resetLastActionTime();
            switch (packet.getAction()) {
                case START_SPRINTING:
                    this.player.setSprinting(true);
                    break;
                case STOP_SPRINTING:
                    this.player.setSprinting(false);
                    break;
                case STOP_SLEEPING:
                    if (this.player.isSleeping()) {
                        this.player.stopSleepInBed(false, true);
                        this.awaitingPositionFromClient = this.player.position();
                    }
                    break;
                case START_RIDING_JUMP:
                    if (this.player.getControlledVehicle() instanceof PlayerRideableJumping playerRideableJumping) {
                        int data = packet.getData();
                        if (playerRideableJumping.canJump() && data > 0) {
                            playerRideableJumping.handleStartJump(data);
                        }
                    }
                    break;
                case STOP_RIDING_JUMP:
                    if (this.player.getControlledVehicle() instanceof PlayerRideableJumping playerRideableJumping) {
                        playerRideableJumping.handleStopJump();
                    }
                    break;
                case OPEN_INVENTORY:
                    if (this.player.getVehicle() instanceof HasCustomInventoryScreen hasCustomInventoryScreen) {
                        hasCustomInventoryScreen.openCustomInventoryScreen(this.player);
                    }
                    break;
                case START_FALL_FLYING:
                    if (!this.player.tryToStartFallFlying()) {
                        this.player.stopFallFlying();
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Invalid client command!");
            }
        }
    }

    public void sendPlayerChatMessage(PlayerChatMessage chatMessage, ChatType.Bound boundChatType) {
        this.send(
            new ClientboundPlayerChatPacket(
                this.nextChatIndex++,
                chatMessage.link().sender(),
                chatMessage.link().index(),
                chatMessage.signature(),
                chatMessage.signedBody().pack(this.messageSignatureCache),
                chatMessage.unsignedContent(),
                chatMessage.filterMask(),
                boundChatType
            )
        );
        MessageSignature messageSignature = chatMessage.signature();
        if (messageSignature != null) {
            this.messageSignatureCache.push(chatMessage.signedBody(), chatMessage.signature());
            int i;
            synchronized (this.lastSeenMessages) {
                this.lastSeenMessages.addPending(messageSignature);
                i = this.lastSeenMessages.trackedMessagesCount();
            }

            if (i > 4096) {
                this.disconnect(Component.translatable("multiplayer.disconnect.too_many_pending_chats"));
            }
        }
    }

    public void sendDisguisedChatMessage(Component message, ChatType.Bound boundChatType) {
        this.send(new ClientboundDisguisedChatPacket(message, boundChatType));
    }

    public SocketAddress getRemoteAddress() {
        return this.connection.getRemoteAddress();
    }

    public void switchToConfig() {
        this.waitingForSwitchToConfig = true;
        this.removePlayerFromWorld();
        this.send(ClientboundStartConfigurationPacket.INSTANCE);
        this.connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
    }

    @Override
    public void handlePingRequest(ServerboundPingRequestPacket packet) {
        this.connection.send(new ClientboundPongResponsePacket(packet.getTime()));
    }

    @Override
    public void handleInteract(ServerboundInteractPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.hasClientLoaded()) {
            final ServerLevel serverLevel = this.player.level();
            final Entity target = packet.getTarget(serverLevel);
            this.player.resetLastActionTime();
            this.player.setShiftKeyDown(packet.isUsingSecondaryAction());
            if (target != null) {
                if (!serverLevel.getWorldBorder().isWithinBounds(target.blockPosition())) {
                    return;
                }

                AABB boundingBox = target.getBoundingBox();
                if (packet.isWithinRange(this.player, boundingBox, 3.0)) {
                    packet.dispatch(
                        new ServerboundInteractPacket.Handler() {
                            private void performInteraction(InteractionHand hand, ServerGamePacketListenerImpl.EntityInteraction entityInteraction) {
                                ItemStack itemInHand = ServerGamePacketListenerImpl.this.player.getItemInHand(hand);
                                if (itemInHand.isItemEnabled(serverLevel.enabledFeatures())) {
                                    ItemStack itemStack = itemInHand.copy();
                                    if (entityInteraction.run(ServerGamePacketListenerImpl.this.player, target, hand) instanceof InteractionResult.Success success
                                        )
                                     {
                                        ItemStack itemStack1 = success.wasItemInteraction() ? itemStack : ItemStack.EMPTY;
                                        CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY.trigger(ServerGamePacketListenerImpl.this.player, itemStack1, target);
                                        if (success.swingSource() == InteractionResult.SwingSource.SERVER) {
                                            ServerGamePacketListenerImpl.this.player.swing(hand, true);
                                        }
                                    }
                                }
                            }

                            @Override
                            public void onInteraction(InteractionHand hand) {
                                this.performInteraction(hand, Player::interactOn);
                            }

                            @Override
                            public void onInteraction(InteractionHand hand, Vec3 interactionLocation) {
                                this.performInteraction(
                                    hand, (player, entity, interactionHand) -> entity.interactAt(player, interactionLocation, interactionHand)
                                );
                            }

                            @Override
                            public void onAttack() {
                                if (!(target instanceof ItemEntity)
                                    && !(target instanceof ExperienceOrb)
                                    && target != ServerGamePacketListenerImpl.this.player
                                    && !(target instanceof AbstractArrow abstractArrow && !abstractArrow.isAttackable())) {
                                    ItemStack itemInHand = ServerGamePacketListenerImpl.this.player.getItemInHand(InteractionHand.MAIN_HAND);
                                    if (itemInHand.isItemEnabled(serverLevel.enabledFeatures())) {
                                        if (!ServerGamePacketListenerImpl.this.player.cannotAttackWithItem(itemInHand, 5)) {
                                            ServerGamePacketListenerImpl.this.player.attack(target);
                                        }
                                    }
                                } else {
                                    ServerGamePacketListenerImpl.this.disconnect(Component.translatable("multiplayer.disconnect.invalid_entity_attacked"));
                                    ServerGamePacketListenerImpl.LOGGER
                                        .warn("Player {} tried to attack an invalid entity", ServerGamePacketListenerImpl.this.player.getPlainTextName());
                                }
                            }
                        }
                    );
                }
            }
        }
    }

    @Override
    public void handleClientCommand(ServerboundClientCommandPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.resetLastActionTime();
        ServerboundClientCommandPacket.Action action = packet.getAction();
        switch (action) {
            case PERFORM_RESPAWN:
                if (this.player.wonGame) {
                    this.player.wonGame = false;
                    this.player = this.server.getPlayerList().respawn(this.player, true, Entity.RemovalReason.CHANGED_DIMENSION);
                    this.resetPosition();
                    this.restartClientLoadTimerAfterRespawn();
                    CriteriaTriggers.CHANGED_DIMENSION.trigger(this.player, Level.END, Level.OVERWORLD);
                } else {
                    if (this.player.getHealth() > 0.0F) {
                        return;
                    }

                    this.player = this.server.getPlayerList().respawn(this.player, false, Entity.RemovalReason.KILLED);
                    this.resetPosition();
                    this.restartClientLoadTimerAfterRespawn();
                    if (this.server.isHardcore()) {
                        this.player.setGameMode(GameType.SPECTATOR);
                        this.player.level().getGameRules().set(GameRules.SPECTATORS_GENERATE_CHUNKS, false, this.server);
                    }
                }
                break;
            case REQUEST_STATS:
                this.player.getStats().sendStats(this.player);
        }
    }

    @Override
    public void handleContainerClose(ServerboundContainerClosePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.doCloseContainer();
    }

    @Override
    public void handleContainerClick(ServerboundContainerClickPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.resetLastActionTime();
        if (this.player.containerMenu.containerId == packet.containerId()) {
            if (this.player.isSpectator()) {
                this.player.containerMenu.sendAllDataToRemote();
            } else if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
                int slotNum = packet.slotNum();
                if (!this.player.containerMenu.isValidSlotIndex(slotNum)) {
                    LOGGER.debug(
                        "Player {} clicked invalid slot index: {}, available slots: {}",
                        this.player.getPlainTextName(),
                        slotNum,
                        this.player.containerMenu.slots.size()
                    );
                } else {
                    boolean flag = packet.stateId() != this.player.containerMenu.getStateId();
                    this.player.containerMenu.suppressRemoteUpdates();
                    this.player.containerMenu.clicked(slotNum, packet.buttonNum(), packet.clickType(), this.player);

                    for (Entry<HashedStack> entry : Int2ObjectMaps.fastIterable(packet.changedSlots())) {
                        this.player.containerMenu.setRemoteSlotUnsafe(entry.getIntKey(), entry.getValue());
                    }

                    this.player.containerMenu.setRemoteCarried(packet.carriedItem());
                    this.player.containerMenu.resumeRemoteUpdates();
                    if (flag) {
                        this.player.containerMenu.broadcastFullState();
                    } else {
                        this.player.containerMenu.broadcastChanges();
                    }
                }
            }
        }
    }

    @Override
    public void handlePlaceRecipe(ServerboundPlaceRecipePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.resetLastActionTime();
        if (!this.player.isSpectator() && this.player.containerMenu.containerId == packet.containerId()) {
            if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
                RecipeManager.ServerDisplayInfo recipeFromDisplay = this.server.getRecipeManager().getRecipeFromDisplay(packet.recipe());
                if (recipeFromDisplay != null) {
                    RecipeHolder<?> recipeHolder = recipeFromDisplay.parent();
                    if (this.player.getRecipeBook().contains(recipeHolder.id())) {
                        if (this.player.containerMenu instanceof RecipeBookMenu recipeBookMenu) {
                            if (recipeHolder.value().placementInfo().isImpossibleToPlace()) {
                                LOGGER.debug("Player {} tried to place impossible recipe {}", this.player, recipeHolder.id().identifier());
                                return;
                            }

                            RecipeBookMenu.PostPlaceAction postPlaceAction = recipeBookMenu.handlePlacement(
                                packet.useMaxItems(), this.player.isCreative(), recipeHolder, this.player.level(), this.player.getInventory()
                            );
                            if (postPlaceAction == RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE) {
                                this.send(new ClientboundPlaceGhostRecipePacket(this.player.containerMenu.containerId, recipeFromDisplay.display().display()));
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handleContainerButtonClick(ServerboundContainerButtonClickPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.resetLastActionTime();
        if (this.player.containerMenu.containerId == packet.containerId() && !this.player.isSpectator()) {
            if (!this.player.containerMenu.stillValid(this.player)) {
                LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
                boolean flag = this.player.containerMenu.clickMenuButton(this.player, packet.buttonId());
                if (flag) {
                    this.player.containerMenu.broadcastChanges();
                }
            }
        }
    }

    @Override
    public void handleSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.hasInfiniteMaterials()) {
            boolean flag = packet.slotNum() < 0;
            ItemStack itemStack = packet.itemStack();
            if (!itemStack.isItemEnabled(this.player.level().enabledFeatures())) {
                return;
            }

            boolean flag1 = packet.slotNum() >= 1 && packet.slotNum() <= 45;
            boolean flag2 = itemStack.isEmpty() || itemStack.getCount() <= itemStack.getMaxStackSize();
            if (flag1 && flag2) {
                this.player.inventoryMenu.getSlot(packet.slotNum()).setByPlayer(itemStack);
                this.player.inventoryMenu.setRemoteSlot(packet.slotNum(), itemStack);
                this.player.inventoryMenu.broadcastChanges();
            } else if (flag && flag2) {
                if (this.dropSpamThrottler.isUnderThreshold()) {
                    this.dropSpamThrottler.increment();
                    this.player.drop(itemStack, true);
                } else {
                    LOGGER.warn("Player {} was dropping items too fast in creative mode, ignoring.", this.player.getPlainTextName());
                }
            }
        }
    }

    @Override
    public void handleSignUpdate(ServerboundSignUpdatePacket packet) {
        List<String> list = Stream.of(packet.getLines()).map(ChatFormatting::stripFormatting).collect(Collectors.toList());
        this.filterTextPacket(list).thenAcceptAsync(texts -> this.updateSignText(packet, (List<FilteredText>)texts), this.server);
    }

    private void updateSignText(ServerboundSignUpdatePacket packet, List<FilteredText> filteredText) {
        this.player.resetLastActionTime();
        ServerLevel serverLevel = this.player.level();
        BlockPos pos = packet.getPos();
        if (serverLevel.hasChunkAt(pos)) {
            if (!(serverLevel.getBlockEntity(pos) instanceof SignBlockEntity signBlockEntity)) {
                return;
            }

            signBlockEntity.updateSignText(this.player, packet.isFrontText(), filteredText);
        }
    }

    @Override
    public void handlePlayerAbilities(ServerboundPlayerAbilitiesPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.getAbilities().flying = packet.isFlying() && this.player.getAbilities().mayfly;
    }

    @Override
    public void handleClientInformation(ServerboundClientInformationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        boolean isModelPartShown = this.player.isModelPartShown(PlayerModelPart.HAT);
        this.player.updateOptions(packet.information());
        if (this.player.isModelPartShown(PlayerModelPart.HAT) != isModelPartShown) {
            this.server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT, this.player));
        }
    }

    @Override
    public void handleChangeDifficulty(ServerboundChangeDifficultyPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) && !this.isSingleplayerOwner()) {
            LOGGER.warn(
                "Player {} tried to change difficulty to {} without required permissions",
                this.player.getGameProfile().name(),
                packet.difficulty().getDisplayName()
            );
        } else {
            this.server.setDifficulty(packet.difficulty(), false);
        }
    }

    @Override
    public void handleChangeGameMode(ServerboundChangeGameModePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!GameModeCommand.PERMISSION_CHECK.check(this.player.permissions())) {
            LOGGER.warn(
                "Player {} tried to change game mode to {} without required permissions",
                this.player.getGameProfile().name(),
                packet.mode().getShortDisplayName().getString()
            );
        } else {
            GameModeCommand.setGameMode(this.player, packet.mode());
        }
    }

    @Override
    public void handleLockDifficulty(ServerboundLockDifficultyPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || this.isSingleplayerOwner()) {
            this.server.setDifficultyLocked(packet.isLocked());
        }
    }

    @Override
    public void handleChatSessionUpdate(ServerboundChatSessionUpdatePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        RemoteChatSession.Data data = packet.chatSession();
        ProfilePublicKey.Data data1 = this.chatSession != null ? this.chatSession.profilePublicKey().data() : null;
        ProfilePublicKey.Data data2 = data.profilePublicKey();
        if (!Objects.equals(data1, data2)) {
            if (data1 != null && data2.expiresAt().isBefore(data1.expiresAt())) {
                this.disconnect(ProfilePublicKey.EXPIRED_PROFILE_PUBLIC_KEY);
            } else {
                try {
                    SignatureValidator signatureValidator = this.server.services().profileKeySignatureValidator();
                    if (signatureValidator == null) {
                        LOGGER.warn("Ignoring chat session from {} due to missing Services public key", this.player.getGameProfile().name());
                        return;
                    }

                    this.resetPlayerChatState(data.validate(this.player.getGameProfile(), signatureValidator));
                } catch (ProfilePublicKey.ValidationException var6) {
                    LOGGER.error("Failed to validate profile key: {}", var6.getMessage());
                    this.disconnect(var6.getComponent());
                }
            }
        }
    }

    @Override
    public void handleConfigurationAcknowledged(ServerboundConfigurationAcknowledgedPacket packet) {
        if (!this.waitingForSwitchToConfig) {
            throw new IllegalStateException("Client acknowledged config, but none was requested");
        } else {
            this.connection
                .setupInboundProtocol(
                    ConfigurationProtocols.SERVERBOUND,
                    new ServerConfigurationPacketListenerImpl(this.server, this.connection, this.createCookie(this.player.clientInformation()))
                );
        }
    }

    @Override
    public void handleChunkBatchReceived(ServerboundChunkBatchReceivedPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.chunkSender.onChunkBatchReceivedByClient(packet.desiredChunksPerTick());
    }

    @Override
    public void handleDebugSubscriptionRequest(ServerboundDebugSubscriptionRequestPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        this.player.requestDebugSubscriptions(packet.subscriptions());
    }

    private void resetPlayerChatState(RemoteChatSession chatSession) {
        this.chatSession = chatSession;
        this.signedMessageDecoder = chatSession.createMessageDecoder(this.player.getUUID());
        this.chatMessageChain
            .append(
                () -> {
                    this.player.setChatSession(chatSession);
                    this.server
                        .getPlayerList()
                        .broadcastAll(
                            new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT), List.of(this.player))
                        );
                }
            );
    }

    @Override
    public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
    }

    @Override
    public void handleClientTickEnd(ServerboundClientTickEndPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
        if (!this.receivedMovementThisTick) {
            this.player.setKnownMovement(Vec3.ZERO);
        }

        this.receivedMovementThisTick = false;
    }

    private void handlePlayerKnownMovement(Vec3 movement) {
        if (movement.lengthSqr() > 1.0E-5F) {
            this.player.resetLastActionTime();
        }

        this.player.setKnownMovement(movement);
        this.receivedMovementThisTick = true;
    }

    @Override
    public boolean hasInfiniteMaterials() {
        return this.player.hasInfiniteMaterials();
    }

    @Override
    public ServerPlayer getPlayer() {
        return this.player;
    }

    public boolean hasClientLoaded() {
        return !this.waitingForRespawn && this.clientLoadedTimeoutTimer <= 0;
    }

    public void tickClientLoadTimeout() {
        if (this.clientLoadedTimeoutTimer > 0) {
            this.clientLoadedTimeoutTimer--;
        }
    }

    private void markClientLoaded() {
        this.clientLoadedTimeoutTimer = 0;
    }

    public void markClientUnloadedAfterDeath() {
        this.waitingForRespawn = true;
    }

    public void restartClientLoadTimerAfterRespawn() {
        this.waitingForRespawn = false;
        this.clientLoadedTimeoutTimer = 60;
    }

    @FunctionalInterface
    interface EntityInteraction {
        InteractionResult run(ServerPlayer player, Entity entity, InteractionHand hand);
    }
}
