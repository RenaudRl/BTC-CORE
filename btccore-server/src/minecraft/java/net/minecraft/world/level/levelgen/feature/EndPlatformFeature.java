package net.minecraft.world.level.levelgen.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public class EndPlatformFeature extends Feature<NoneFeatureConfiguration> {
    public EndPlatformFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        createEndPlatform(context.level(), context.origin(), false);
        return true;
    }

    public static void createEndPlatform(ServerLevelAccessor level, BlockPos pos, boolean dropBlocks) {
        // CraftBukkit start
        createEndPlatform(level, pos, dropBlocks, null);
    }

    public static void createEndPlatform(ServerLevelAccessor level, BlockPos pos, boolean dropBlocks, @javax.annotation.Nullable net.minecraft.world.entity.Entity entity) {
        org.bukkit.craftbukkit.util.BlockStateListPopulator blockList = new org.bukkit.craftbukkit.util.BlockStateListPopulator(level);
        // CraftBukkit end
        BlockPos.MutableBlockPos mutableBlockPos = pos.mutable();

        for (int i = -2; i <= 2; i++) {
            for (int i1 = -2; i1 <= 2; i1++) {
                for (int i2 = -1; i2 < 3; i2++) {
                    BlockPos blockPos = mutableBlockPos.set(pos).move(i1, i2, i);
                    Block block = i2 == -1 ? Blocks.OBSIDIAN : Blocks.AIR;
                    if (!blockList.getBlockState(blockPos).is(block)) { // CraftBukkit
                        if (dropBlocks) {
                            blockList.destroyBlock(blockPos, true, null); // CraftBukkit
                        }

                        blockList.setBlock(blockPos, block.defaultBlockState(), 3); // CraftBukkit
                    }
                }
            }
        }

        // CraftBukkit start
        // SPIGOT-7746: Entity will only be null during world generation, which is async, so just generate without event
        if (entity != null) {
            org.bukkit.World bworld = level.getLevel().getWorld();
            org.bukkit.event.world.PortalCreateEvent portalEvent = new org.bukkit.event.world.PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) blockList.getSnapshotBlocks(), bworld, entity.getBukkitEntity(), org.bukkit.event.world.PortalCreateEvent.CreateReason.END_PLATFORM);
            level.getLevel().getCraftServer().getPluginManager().callEvent(portalEvent);
            if (portalEvent.isCancelled()) return;
        }

        if (dropBlocks) {
            blockList.placeBlocks(state -> level.destroyBlock(state.getPosition(), true, null));
        } else {
            blockList.placeBlocks();
        }
        // CraftBukkit end
    }
}
