package net.minecraft.world.level.chunk;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public class LevelChunkSection {
    public static final int SECTION_WIDTH = 16;
    public static final int SECTION_HEIGHT = 16;
    public static final int SECTION_SIZE = 4096;
    public static final int BIOME_CONTAINER_BITS = 2;
    private short nonEmptyBlockCount;
    private short tickingBlockCount;
    private short tickingFluidCount;
    public final PalettedContainer<BlockState> states;
    private PalettedContainerRO<Holder<Biome>> biomes;

    private LevelChunkSection(LevelChunkSection section) {
        this.nonEmptyBlockCount = section.nonEmptyBlockCount;
        this.tickingBlockCount = section.tickingBlockCount;
        this.tickingFluidCount = section.tickingFluidCount;
        this.states = section.states.copy();
        this.biomes = section.biomes.copy();
    }

    public LevelChunkSection(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
        this.states = states;
        this.biomes = biomes;
        this.recalcBlockCounts();
    }

    public LevelChunkSection(PalettedContainerFactory containerFactory) {
        this.states = containerFactory.createForBlockStates();
        this.biomes = containerFactory.createForBiomes();
    }

    public BlockState getBlockState(int x, int y, int z) {
        return this.states.get(x, y, z);
    }

    public FluidState getFluidState(int x, int y, int z) {
        return this.states.get(x, y, z).getFluidState();
    }

    public void acquire() {
        this.states.acquire();
    }

    public void release() {
        this.states.release();
    }

    public BlockState setBlockState(int x, int y, int z, BlockState state) {
        return this.setBlockState(x, y, z, state, true);
    }

    public BlockState setBlockState(int x, int y, int z, BlockState state, boolean useLocks) {
        BlockState blockState;
        if (useLocks) {
            blockState = this.states.getAndSet(x, y, z, state);
        } else {
            blockState = this.states.getAndSetUnchecked(x, y, z, state);
        }

        FluidState fluidState = blockState.getFluidState();
        FluidState fluidState1 = state.getFluidState();
        if (!blockState.isAir()) {
            this.nonEmptyBlockCount--;
            if (blockState.isRandomlyTicking()) {
                this.tickingBlockCount--;
            }
        }

        if (!fluidState.isEmpty()) {
            this.tickingFluidCount--;
        }

        if (!state.isAir()) {
            this.nonEmptyBlockCount++;
            if (state.isRandomlyTicking()) {
                this.tickingBlockCount++;
            }
        }

        if (!fluidState1.isEmpty()) {
            this.tickingFluidCount++;
        }

        return blockState;
    }

    public boolean hasOnlyAir() {
        return this.nonEmptyBlockCount == 0;
    }

    public boolean isRandomlyTicking() {
        return this.isRandomlyTickingBlocks() || this.isRandomlyTickingFluids();
    }

    public boolean isRandomlyTickingBlocks() {
        return this.tickingBlockCount > 0;
    }

    public boolean isRandomlyTickingFluids() {
        return this.tickingFluidCount > 0;
    }

    public void recalcBlockCounts() {
        class BlockCounter implements PalettedContainer.CountConsumer<BlockState> {
            public int nonEmptyBlockCount;
            public int tickingBlockCount;
            public int tickingFluidCount;

            @Override
            public void accept(BlockState state, int count) {
                FluidState fluidState = state.getFluidState();
                if (!state.isAir()) {
                    this.nonEmptyBlockCount += count;
                    if (state.isRandomlyTicking()) {
                        this.tickingBlockCount += count;
                    }
                }

                if (!fluidState.isEmpty()) {
                    this.nonEmptyBlockCount += count;
                    if (fluidState.isRandomlyTicking()) {
                        this.tickingFluidCount += count;
                    }
                }
            }
        }

        BlockCounter blockCounter = new BlockCounter();
        this.states.count(blockCounter);
        this.nonEmptyBlockCount = (short)blockCounter.nonEmptyBlockCount;
        this.tickingBlockCount = (short)blockCounter.tickingBlockCount;
        this.tickingFluidCount = (short)blockCounter.tickingFluidCount;
    }

    public PalettedContainer<BlockState> getStates() {
        return this.states;
    }

    public PalettedContainerRO<Holder<Biome>> getBiomes() {
        return this.biomes;
    }

    public void read(FriendlyByteBuf buffer) {
        this.nonEmptyBlockCount = buffer.readShort();
        this.states.read(buffer);
        PalettedContainer<Holder<Biome>> palettedContainer = this.biomes.recreate();
        palettedContainer.read(buffer);
        this.biomes = palettedContainer;
    }

    public void readBiomes(FriendlyByteBuf buffer) {
        PalettedContainer<Holder<Biome>> palettedContainer = this.biomes.recreate();
        palettedContainer.read(buffer);
        this.biomes = palettedContainer;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeShort(this.nonEmptyBlockCount);
        this.states.write(buffer);
        this.biomes.write(buffer);
    }

    public int getSerializedSize() {
        return 2 + this.states.getSerializedSize() + this.biomes.getSerializedSize();
    }

    public boolean maybeHas(Predicate<BlockState> predicate) {
        return this.states.maybeHas(predicate);
    }

    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        return this.biomes.get(x, y, z);
    }

    public void fillBiomesFromNoise(BiomeResolver biomeResolver, Climate.Sampler climateSampler, int x, int y, int z) {
        PalettedContainer<Holder<Biome>> palettedContainer = this.biomes.recreate();
        int i = 4;

        for (int i1 = 0; i1 < 4; i1++) {
            for (int i2 = 0; i2 < 4; i2++) {
                for (int i3 = 0; i3 < 4; i3++) {
                    palettedContainer.getAndSetUnchecked(i1, i2, i3, biomeResolver.getNoiseBiome(x + i1, y + i2, z + i3, climateSampler));
                }
            }
        }

        this.biomes = palettedContainer;
    }

    public LevelChunkSection copy() {
        return new LevelChunkSection(this);
    }
}
