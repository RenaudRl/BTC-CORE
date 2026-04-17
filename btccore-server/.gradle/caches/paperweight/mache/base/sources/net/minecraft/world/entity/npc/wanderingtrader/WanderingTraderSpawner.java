package net.minecraft.world.entity.npc.wanderingtrader;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ServerLevelData;
import org.jspecify.annotations.Nullable;

public class WanderingTraderSpawner implements CustomSpawner {
    private static final int DEFAULT_TICK_DELAY = 1200;
    public static final int DEFAULT_SPAWN_DELAY = 24000;
    private static final int MIN_SPAWN_CHANCE = 25;
    private static final int MAX_SPAWN_CHANCE = 75;
    private static final int SPAWN_CHANCE_INCREASE = 25;
    private static final int SPAWN_ONE_IN_X_CHANCE = 10;
    private static final int NUMBER_OF_SPAWN_ATTEMPTS = 10;
    private final RandomSource random = RandomSource.create();
    private final ServerLevelData serverLevelData;
    private int tickDelay;
    private int spawnDelay;
    private int spawnChance;

    public WanderingTraderSpawner(ServerLevelData serverLevelData) {
        this.serverLevelData = serverLevelData;
        this.tickDelay = 1200;
        this.spawnDelay = serverLevelData.getWanderingTraderSpawnDelay();
        this.spawnChance = serverLevelData.getWanderingTraderSpawnChance();
        if (this.spawnDelay == 0 && this.spawnChance == 0) {
            this.spawnDelay = 24000;
            serverLevelData.setWanderingTraderSpawnDelay(this.spawnDelay);
            this.spawnChance = 25;
            serverLevelData.setWanderingTraderSpawnChance(this.spawnChance);
        }
    }

    @Override
    public void tick(ServerLevel level, boolean spawnEnemies) {
        if (level.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS)) {
            if (--this.tickDelay <= 0) {
                this.tickDelay = 1200;
                this.spawnDelay -= 1200;
                this.serverLevelData.setWanderingTraderSpawnDelay(this.spawnDelay);
                if (this.spawnDelay <= 0) {
                    this.spawnDelay = 24000;
                    int i = this.spawnChance;
                    this.spawnChance = Mth.clamp(this.spawnChance + 25, 25, 75);
                    this.serverLevelData.setWanderingTraderSpawnChance(this.spawnChance);
                    if (this.random.nextInt(100) <= i) {
                        if (this.spawn(level)) {
                            this.spawnChance = 25;
                        }
                    }
                }
            }
        }
    }

    private boolean spawn(ServerLevel level) {
        Player randomPlayer = level.getRandomPlayer();
        if (randomPlayer == null) {
            return true;
        } else if (this.random.nextInt(10) != 0) {
            return false;
        } else {
            BlockPos blockPos = randomPlayer.blockPosition();
            int i = 48;
            PoiManager poiManager = level.getPoiManager();
            Optional<BlockPos> optional = poiManager.find(holder -> holder.is(PoiTypes.MEETING), blockPos3 -> true, blockPos, 48, PoiManager.Occupancy.ANY);
            BlockPos blockPos1 = optional.orElse(blockPos);
            BlockPos blockPos2 = this.findSpawnPositionNear(level, blockPos1, 48);
            if (blockPos2 != null && this.hasEnoughSpace(level, blockPos2)) {
                if (level.getBiome(blockPos2).is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) {
                    return false;
                }

                WanderingTrader wanderingTrader = EntityType.WANDERING_TRADER.spawn(level, blockPos2, EntitySpawnReason.EVENT);
                if (wanderingTrader != null) {
                    for (int i1 = 0; i1 < 2; i1++) {
                        this.tryToSpawnLlamaFor(level, wanderingTrader, 4);
                    }

                    this.serverLevelData.setWanderingTraderId(wanderingTrader.getUUID());
                    wanderingTrader.setDespawnDelay(48000);
                    wanderingTrader.setWanderTarget(blockPos1);
                    wanderingTrader.setHomeTo(blockPos1, 16);
                    return true;
                }
            }

            return false;
        }
    }

    private void tryToSpawnLlamaFor(ServerLevel level, WanderingTrader trader, int maxDistance) {
        BlockPos blockPos = this.findSpawnPositionNear(level, trader.blockPosition(), maxDistance);
        if (blockPos != null) {
            TraderLlama traderLlama = EntityType.TRADER_LLAMA.spawn(level, blockPos, EntitySpawnReason.EVENT);
            if (traderLlama != null) {
                traderLlama.setLeashedTo(trader, true);
            }
        }
    }

    private @Nullable BlockPos findSpawnPositionNear(LevelReader level, BlockPos pos, int maxDistance) {
        BlockPos blockPos = null;
        SpawnPlacementType placementType = SpawnPlacements.getPlacementType(EntityType.WANDERING_TRADER);

        for (int i = 0; i < 10; i++) {
            int i1 = pos.getX() + this.random.nextInt(maxDistance * 2) - maxDistance;
            int i2 = pos.getZ() + this.random.nextInt(maxDistance * 2) - maxDistance;
            int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, i1, i2);
            BlockPos blockPos1 = new BlockPos(i1, height, i2);
            if (placementType.isSpawnPositionOk(level, blockPos1, EntityType.WANDERING_TRADER)) {
                blockPos = blockPos1;
                break;
            }
        }

        return blockPos;
    }

    private boolean hasEnoughSpace(BlockGetter level, BlockPos pos) {
        for (BlockPos blockPos : BlockPos.betweenClosed(pos, pos.offset(1, 2, 1))) {
            if (!level.getBlockState(blockPos).getCollisionShape(level, blockPos).isEmpty()) {
                return false;
            }
        }

        return true;
    }
}
