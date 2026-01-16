package net.minecraft.world.level.block.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.EndGatewayConfiguration;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class TheEndGatewayBlockEntity extends TheEndPortalBlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SPAWN_TIME = 200;
    private static final int COOLDOWN_TIME = 40;
    private static final int ATTENTION_INTERVAL = 2400;
    private static final int EVENT_COOLDOWN = 1;
    private static final int GATEWAY_HEIGHT_ABOVE_SURFACE = 10;
    private static final long DEFAULT_AGE = 0L;
    private static final boolean DEFAULT_EXACT_TELEPORT = false;
    public long age = 0L;
    private int teleportCooldown;
    public @Nullable BlockPos exitPortal;
    public boolean exactTeleport = false;

    public TheEndGatewayBlockEntity(BlockPos pos, BlockState blockState) {
        super(BlockEntityType.END_GATEWAY, pos, blockState);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("Age", this.age);
        output.storeNullable("exit_portal", BlockPos.CODEC, this.exitPortal);
        if (this.exactTeleport) {
            output.putBoolean("ExactTeleport", true);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.age = input.getLongOr("Age", 0L);
        this.exitPortal = input.read("exit_portal", BlockPos.CODEC).filter(Level::isInSpawnableBounds).orElse(null);
        this.exactTeleport = input.getBooleanOr("ExactTeleport", false);
    }

    public static void beamAnimationTick(Level level, BlockPos pos, BlockState state, TheEndGatewayBlockEntity blockEntity) {
        blockEntity.age++;
        if (blockEntity.isCoolingDown()) {
            blockEntity.teleportCooldown--;
        }
    }

    public static void portalTick(Level level, BlockPos pos, BlockState state, TheEndGatewayBlockEntity blockEntity) {
        boolean isSpawning = blockEntity.isSpawning();
        boolean isCoolingDown = blockEntity.isCoolingDown();
        blockEntity.age++;
        if (isCoolingDown) {
            blockEntity.teleportCooldown--;
        } else if (blockEntity.age % 2400L == 0L) {
            triggerCooldown(level, pos, state, blockEntity);
        }

        if (isSpawning != blockEntity.isSpawning() || isCoolingDown != blockEntity.isCoolingDown()) {
            setChanged(level, pos, state);
        }
    }

    public boolean isSpawning() {
        return this.age < 200L;
    }

    public boolean isCoolingDown() {
        return this.teleportCooldown > 0;
    }

    public float getSpawnPercent(float partialTick) {
        return Mth.clamp(((float)this.age + partialTick) / 200.0F, 0.0F, 1.0F);
    }

    public float getCooldownPercent(float partialTick) {
        return 1.0F - Mth.clamp((this.teleportCooldown - partialTick) / 40.0F, 0.0F, 1.0F);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    public static void triggerCooldown(Level level, BlockPos pos, BlockState state, TheEndGatewayBlockEntity blockEntity) {
        if (!level.isClientSide()) {
            blockEntity.teleportCooldown = 40;
            level.blockEvent(pos, state.getBlock(), 1, 0);
            setChanged(level, pos, state);
        }
    }

    @Override
    public boolean triggerEvent(int id, int type) {
        if (id == 1) {
            this.teleportCooldown = 40;
            return true;
        } else {
            return super.triggerEvent(id, type);
        }
    }

    public @Nullable Vec3 getPortalPosition(ServerLevel level, BlockPos pos) {
        if (this.exitPortal == null && level.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.END) { // CraftBukkit - work in alternate worlds
            BlockPos blockPos = findOrCreateValidTeleportPos(level, pos);
            blockPos = blockPos.above(10);
            LOGGER.debug("Creating portal at {}", blockPos);
            spawnGatewayPortal(level, blockPos, EndGatewayConfiguration.knownExit(pos, false));
            this.setExitPosition(blockPos, this.exactTeleport);
        }

        if (this.exitPortal != null) {
            BlockPos blockPos = this.exactTeleport ? this.exitPortal : findExitPosition(level, this.exitPortal);
            return blockPos.getBottomCenter();
        } else {
            return null;
        }
    }

    private static BlockPos findExitPosition(Level level, BlockPos pos) {
        BlockPos blockPos = findTallestBlock(level, pos.offset(0, 2, 0), 5, false);
        LOGGER.debug("Best exit position for portal at {} is {}", pos, blockPos);
        return blockPos.above();
    }

    private static BlockPos findOrCreateValidTeleportPos(ServerLevel level, BlockPos pos) {
        Vec3 vec3 = findExitPortalXZPosTentative(level, pos);
        LevelChunk chunk = getChunk(level, vec3);
        BlockPos blockPos = findValidSpawnInChunk(chunk);
        if (blockPos == null) {
            BlockPos blockPos1 = BlockPos.containing(vec3.x + 0.5, 75.0, vec3.z + 0.5);
            LOGGER.debug("Failed to find a suitable block to teleport to, spawning an island on {}", blockPos1);
            level.registryAccess()
                .lookup(Registries.CONFIGURED_FEATURE)
                .flatMap(registry -> registry.get(EndFeatures.END_ISLAND))
                .ifPresent(
                    reference -> reference.value().place(level, level.getChunkSource().getGenerator(), RandomSource.create(blockPos1.asLong()), blockPos1)
                );
            blockPos = blockPos1;
        } else {
            LOGGER.debug("Found suitable block to teleport to: {}", blockPos);
        }

        return findTallestBlock(level, blockPos, 16, true);
    }

    private static Vec3 findExitPortalXZPosTentative(ServerLevel level, BlockPos pos) {
        Vec3 vec3 = new Vec3(pos.getX(), 0.0, pos.getZ()).normalize();
        int i = 1024;
        Vec3 vec31 = vec3.scale(1024.0);

        for (int i1 = 16; !isChunkEmpty(level, vec31) && i1-- > 0; vec31 = vec31.add(vec3.scale(-16.0))) {
            LOGGER.debug("Skipping backwards past nonempty chunk at {}", vec31);
        }

        for (int var6 = 16; isChunkEmpty(level, vec31) && var6-- > 0; vec31 = vec31.add(vec3.scale(16.0))) {
            LOGGER.debug("Skipping forward past empty chunk at {}", vec31);
        }

        LOGGER.debug("Found chunk at {}", vec31);
        return vec31;
    }

    private static boolean isChunkEmpty(ServerLevel level, Vec3 pos) {
        return getChunk(level, pos).getHighestFilledSectionIndex() == -1;
    }

    private static BlockPos findTallestBlock(BlockGetter level, BlockPos pos, int radius, boolean allowBedrock) {
        BlockPos blockPos = null;

        for (int i = -radius; i <= radius; i++) {
            for (int i1 = -radius; i1 <= radius; i1++) {
                if (i != 0 || i1 != 0 || allowBedrock) {
                    for (int y = level.getMaxY(); y > (blockPos == null ? level.getMinY() : blockPos.getY()); y--) {
                        BlockPos blockPos1 = new BlockPos(pos.getX() + i, y, pos.getZ() + i1);
                        BlockState blockState = level.getBlockState(blockPos1);
                        if (blockState.isCollisionShapeFullBlock(level, blockPos1) && (allowBedrock || !blockState.is(Blocks.BEDROCK))) {
                            blockPos = blockPos1;
                            break;
                        }
                    }
                }
            }
        }

        return blockPos == null ? pos : blockPos;
    }

    private static LevelChunk getChunk(Level level, Vec3 pos) {
        return level.getChunk(Mth.floor(pos.x / 16.0), Mth.floor(pos.z / 16.0));
    }

    private static @Nullable BlockPos findValidSpawnInChunk(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        BlockPos blockPos = new BlockPos(pos.getMinBlockX(), 30, pos.getMinBlockZ());
        int i = chunk.getHighestSectionPosition() + 16 - 1;
        BlockPos blockPos1 = new BlockPos(pos.getMaxBlockX(), i, pos.getMaxBlockZ());
        BlockPos blockPos2 = null;
        double d = 0.0;

        for (BlockPos blockPos3 : BlockPos.betweenClosed(blockPos, blockPos1)) {
            BlockState blockState = chunk.getBlockState(blockPos3);
            BlockPos blockPos4 = blockPos3.above();
            BlockPos blockPos5 = blockPos3.above(2);
            if (blockState.is(Blocks.END_STONE)
                && !chunk.getBlockState(blockPos4).isCollisionShapeFullBlock(chunk, blockPos4)
                && !chunk.getBlockState(blockPos5).isCollisionShapeFullBlock(chunk, blockPos5)) {
                double d1 = blockPos3.distToCenterSqr(0.0, 0.0, 0.0);
                if (blockPos2 == null || d1 < d) {
                    blockPos2 = blockPos3;
                    d = d1;
                }
            }
        }

        return blockPos2;
    }

    private static void spawnGatewayPortal(ServerLevel level, BlockPos pos, EndGatewayConfiguration config) {
        Feature.END_GATEWAY.place(config, level, level.getChunkSource().getGenerator(), RandomSource.create(), pos);
    }

    @Override
    public boolean shouldRenderFace(Direction face) {
        return Block.shouldRenderFace(this.getBlockState(), this.level.getBlockState(this.getBlockPos().relative(face)), face);
    }

    public int getParticleAmount() {
        int i = 0;

        for (Direction direction : Direction.values()) {
            i += this.shouldRenderFace(direction) ? 1 : 0;
        }

        return i;
    }

    public void setExitPosition(BlockPos exitPortal, boolean exactTeleport) {
        this.exactTeleport = exactTeleport;
        this.exitPortal = exitPortal;
        this.setChanged();
    }
}
