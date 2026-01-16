package net.minecraft.world.entity.ai.village;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class VillageSiege implements CustomSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private boolean hasSetupSiege;
    private VillageSiege.State siegeState = VillageSiege.State.SIEGE_DONE;
    private int zombiesToSpawn;
    private int nextSpawnTime;
    private int spawnX;
    private int spawnY;
    private int spawnZ;

    @Override
    public void tick(ServerLevel level, boolean spawnEnemies) {
        if (!level.isBrightOutside() && spawnEnemies) {
            long l = level.getDayTime() % 24000L;
            if (l == 18000L) {
                this.siegeState = level.random.nextInt(10) == 0 ? VillageSiege.State.SIEGE_TONIGHT : VillageSiege.State.SIEGE_DONE;
            }

            if (this.siegeState != VillageSiege.State.SIEGE_DONE) {
                if (!this.hasSetupSiege) {
                    if (!this.tryToSetupSiege(level)) {
                        return;
                    }

                    this.hasSetupSiege = true;
                }

                if (this.nextSpawnTime > 0) {
                    this.nextSpawnTime--;
                } else {
                    this.nextSpawnTime = 2;
                    if (this.zombiesToSpawn > 0) {
                        this.trySpawn(level);
                        this.zombiesToSpawn--;
                    } else {
                        this.siegeState = VillageSiege.State.SIEGE_DONE;
                    }
                }
            }
        } else {
            this.siegeState = VillageSiege.State.SIEGE_DONE;
            this.hasSetupSiege = false;
        }
    }

    private boolean tryToSetupSiege(ServerLevel level) {
        for (Player player : level.players()) {
            if (!player.isSpectator()) {
                BlockPos blockPos = player.blockPosition();
                if (level.isVillage(blockPos) && !level.getBiome(blockPos).is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) {
                    for (int i = 0; i < 10; i++) {
                        float f = level.random.nextFloat() * (float) (Math.PI * 2);
                        this.spawnX = blockPos.getX() + Mth.floor(Mth.cos(f) * 32.0F);
                        this.spawnY = blockPos.getY();
                        this.spawnZ = blockPos.getZ() + Mth.floor(Mth.sin(f) * 32.0F);
                        if (this.findRandomSpawnPos(level, new BlockPos(this.spawnX, this.spawnY, this.spawnZ)) != null) {
                            this.nextSpawnTime = 0;
                            this.zombiesToSpawn = 20;
                            break;
                        }
                    }

                    return true;
                }
            }
        }

        return false;
    }

    private void trySpawn(ServerLevel level) {
        Vec3 vec3 = this.findRandomSpawnPos(level, new BlockPos(this.spawnX, this.spawnY, this.spawnZ));
        if (vec3 != null) {
            Zombie zombie;
            try {
                zombie = new Zombie(level);
                zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(zombie.blockPosition()), EntitySpawnReason.EVENT, null);
            } catch (Exception var5) {
                LOGGER.warn("Failed to create zombie for village siege at {}", vec3, var5);
                com.destroystokyo.paper.exception.ServerInternalException.reportInternalException(var5); // Paper - ServerExceptionEvent
                return;
            }

            zombie.snapTo(vec3.x, vec3.y, vec3.z, level.random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntityWithPassengers(zombie, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION); // CraftBukkit
        }
    }

    private @Nullable Vec3 findRandomSpawnPos(ServerLevel level, BlockPos pos) {
        for (int i = 0; i < 10; i++) {
            int i1 = pos.getX() + level.random.nextInt(16) - 8;
            int i2 = pos.getZ() + level.random.nextInt(16) - 8;
            int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, i1, i2);
            BlockPos blockPos = new BlockPos(i1, height, i2);
            if (level.isVillage(blockPos) && Monster.checkMonsterSpawnRules(EntityType.ZOMBIE, level, EntitySpawnReason.EVENT, blockPos, level.random)) {
                return Vec3.atBottomCenterOf(blockPos);
            }
        }

        return null;
    }

    static enum State {
        SIEGE_CAN_ACTIVATE,
        SIEGE_TONIGHT,
        SIEGE_DONE;
    }
}
