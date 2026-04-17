package net.minecraft.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

public class PatrolSpawner implements CustomSpawner {
    private int nextTick;

    @Override
    public void tick(ServerLevel level, boolean spawnEnemies) {
        if (spawnEnemies) {
            if (level.getGameRules().get(GameRules.SPAWN_PATROLS)) {
                RandomSource randomSource = level.random;
                this.nextTick--;
                if (this.nextTick <= 0) {
                    this.nextTick = this.nextTick + 12000 + randomSource.nextInt(1200);
                    if (level.isBrightOutside()) {
                        if (randomSource.nextInt(5) == 0) {
                            int size = level.players().size();
                            if (size >= 1) {
                                Player player = level.players().get(randomSource.nextInt(size));
                                if (!player.isSpectator()) {
                                    if (!level.isCloseToVillage(player.blockPosition(), 2)) {
                                        int i = (24 + randomSource.nextInt(24)) * (randomSource.nextBoolean() ? -1 : 1);
                                        int i1 = (24 + randomSource.nextInt(24)) * (randomSource.nextBoolean() ? -1 : 1);
                                        BlockPos.MutableBlockPos mutableBlockPos = player.blockPosition().mutable().move(i, 0, i1);
                                        int i2 = 10;
                                        if (level.hasChunksAt(
                                            mutableBlockPos.getX() - 10, mutableBlockPos.getZ() - 10, mutableBlockPos.getX() + 10, mutableBlockPos.getZ() + 10
                                        )) {
                                            if (level.environmentAttributes().getValue(EnvironmentAttributes.CAN_PILLAGER_PATROL_SPAWN, mutableBlockPos)) {
                                                int i3 = (int)Math.ceil(level.getCurrentDifficultyAt(mutableBlockPos).getEffectiveDifficulty()) + 1;

                                                for (int i4 = 0; i4 < i3; i4++) {
                                                    mutableBlockPos.setY(
                                                        level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mutableBlockPos).getY()
                                                    );
                                                    if (i4 == 0) {
                                                        if (!this.spawnPatrolMember(level, mutableBlockPos, randomSource, true)) {
                                                            break;
                                                        }
                                                    } else {
                                                        this.spawnPatrolMember(level, mutableBlockPos, randomSource, false);
                                                    }

                                                    mutableBlockPos.setX(mutableBlockPos.getX() + randomSource.nextInt(5) - randomSource.nextInt(5));
                                                    mutableBlockPos.setZ(mutableBlockPos.getZ() + randomSource.nextInt(5) - randomSource.nextInt(5));
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

    private boolean spawnPatrolMember(ServerLevel level, BlockPos pos, RandomSource random, boolean leader) {
        BlockState blockState = level.getBlockState(pos);
        if (!NaturalSpawner.isValidEmptySpawnBlock(level, pos, blockState, blockState.getFluidState(), EntityType.PILLAGER)) {
            return false;
        } else if (!PatrollingMonster.checkPatrollingMonsterSpawnRules(EntityType.PILLAGER, level, EntitySpawnReason.PATROL, pos, random)) {
            return false;
        } else {
            PatrollingMonster patrollingMonster = EntityType.PILLAGER.create(level, EntitySpawnReason.PATROL);
            if (patrollingMonster != null) {
                if (leader) {
                    patrollingMonster.setPatrolLeader(true);
                    patrollingMonster.findPatrolTarget();
                }

                patrollingMonster.setPos(pos.getX(), pos.getY(), pos.getZ());
                patrollingMonster.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.PATROL, null);
                level.addFreshEntityWithPassengers(patrollingMonster);
                return true;
            } else {
                return false;
            }
        }
    }
}
