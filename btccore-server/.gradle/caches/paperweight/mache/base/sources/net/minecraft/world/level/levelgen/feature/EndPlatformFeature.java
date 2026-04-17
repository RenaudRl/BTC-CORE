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
        BlockPos.MutableBlockPos mutableBlockPos = pos.mutable();

        for (int i = -2; i <= 2; i++) {
            for (int i1 = -2; i1 <= 2; i1++) {
                for (int i2 = -1; i2 < 3; i2++) {
                    BlockPos blockPos = mutableBlockPos.set(pos).move(i1, i2, i);
                    Block block = i2 == -1 ? Blocks.OBSIDIAN : Blocks.AIR;
                    if (!level.getBlockState(blockPos).is(block)) {
                        if (dropBlocks) {
                            level.destroyBlock(blockPos, true, null);
                        }

                        level.setBlock(blockPos, block.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }
}
