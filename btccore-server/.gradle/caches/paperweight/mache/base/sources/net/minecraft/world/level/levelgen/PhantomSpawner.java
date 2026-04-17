package net.minecraft.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;

public class PhantomSpawner implements CustomSpawner {
    private int nextTick;

    @Override
    public void tick(ServerLevel level, boolean spawnEnemies) {
        if (spawnEnemies) {
            if (level.getGameRules().get(GameRules.SPAWN_PHANTOMS)) {
                RandomSource randomSource = level.random;
                this.nextTick--;
                if (this.nextTick <= 0) {
                    this.nextTick = this.nextTick + (60 + randomSource.nextInt(60)) * 20;
                    if (level.getSkyDarken() >= 5 || !level.dimensionType().hasSkyLight()) {
                        for (ServerPlayer serverPlayer : level.players()) {
                            if (!serverPlayer.isSpectator()) {
                                BlockPos blockPos = serverPlayer.blockPosition();
                                if (!level.dimensionType().hasSkyLight() || blockPos.getY() >= level.getSeaLevel() && level.canSeeSky(blockPos)) {
                                    DifficultyInstance currentDifficultyAt = level.getCurrentDifficultyAt(blockPos);
                                    if (currentDifficultyAt.isHarderThan(randomSource.nextFloat() * 3.0F)) {
                                        ServerStatsCounter stats = serverPlayer.getStats();
                                        int i = Mth.clamp(stats.getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST)), 1, Integer.MAX_VALUE);
                                        int i1 = 24000;
                                        if (randomSource.nextInt(i) >= 72000) {
                                            BlockPos blockPos1 = blockPos.above(20 + randomSource.nextInt(15))
                                                .east(-10 + randomSource.nextInt(21))
                                                .south(-10 + randomSource.nextInt(21));
                                            BlockState blockState = level.getBlockState(blockPos1);
                                            FluidState fluidState = level.getFluidState(blockPos1);
                                            if (NaturalSpawner.isValidEmptySpawnBlock(level, blockPos1, blockState, fluidState, EntityType.PHANTOM)) {
                                                SpawnGroupData spawnGroupData = null;
                                                int i2 = 1 + randomSource.nextInt(currentDifficultyAt.getDifficulty().getId() + 1);

                                                for (int i3 = 0; i3 < i2; i3++) {
                                                    Phantom phantom = EntityType.PHANTOM.create(level, EntitySpawnReason.NATURAL);
                                                    if (phantom != null) {
                                                        phantom.snapTo(blockPos1, 0.0F, 0.0F);
                                                        spawnGroupData = phantom.finalizeSpawn(
                                                            level, currentDifficultyAt, EntitySpawnReason.NATURAL, spawnGroupData
                                                        );
                                                        level.addFreshEntityWithPassengers(phantom);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
