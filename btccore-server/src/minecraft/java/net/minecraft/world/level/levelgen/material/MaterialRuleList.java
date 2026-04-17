package net.minecraft.world.level.levelgen.material;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.jspecify.annotations.Nullable;

public record MaterialRuleList(NoiseChunk.BlockStateFiller[] materialRuleList) implements NoiseChunk.BlockStateFiller {
    @Override
    public @Nullable BlockState calculate(DensityFunction.FunctionContext context) {
        for (NoiseChunk.BlockStateFiller blockStateFiller : this.materialRuleList) {
            BlockState blockState = blockStateFiller.calculate(context);
            if (blockState != null) {
                return blockState;
            }
        }

        return null;
    }
}
