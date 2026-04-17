package net.minecraft.world.level.biome;

import com.google.common.hash.Hashing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;

public class BiomeManager {
    public static final int CHUNK_CENTER_QUART = QuartPos.fromBlock(8);
    private static final int ZOOM_BITS = 2;
    private static final int ZOOM = 4;
    private static final int ZOOM_MASK = 3;
    private final BiomeManager.NoiseBiomeSource noiseBiomeSource;
    private final long biomeZoomSeed;

    public BiomeManager(BiomeManager.NoiseBiomeSource noiseBiomeSource, long biomeZoomSeed) {
        this.noiseBiomeSource = noiseBiomeSource;
        this.biomeZoomSeed = biomeZoomSeed;
    }

    public static long obfuscateSeed(long seed) {
        return Hashing.sha256().hashLong(seed).asLong();
    }

    public BiomeManager withDifferentSource(BiomeManager.NoiseBiomeSource newSource) {
        return new BiomeManager(newSource, this.biomeZoomSeed);
    }

    public Holder<Biome> getBiome(BlockPos pos) {
        int i = pos.getX() - 2;
        int i1 = pos.getY() - 2;
        int i2 = pos.getZ() - 2;
        int i3 = i >> 2;
        int i4 = i1 >> 2;
        int i5 = i2 >> 2;
        double d = (i & 3) / 4.0;
        double d1 = (i1 & 3) / 4.0;
        double d2 = (i2 & 3) / 4.0;
        int i6 = 0;
        double d3 = Double.POSITIVE_INFINITY;

        for (int i7 = 0; i7 < 8; i7++) {
            boolean flag = (i7 & 4) == 0;
            boolean flag1 = (i7 & 2) == 0;
            boolean flag2 = (i7 & 1) == 0;
            int i8 = flag ? i3 : i3 + 1;
            int i9 = flag1 ? i4 : i4 + 1;
            int i10 = flag2 ? i5 : i5 + 1;
            double d4 = flag ? d : d - 1.0;
            double d5 = flag1 ? d1 : d1 - 1.0;
            double d6 = flag2 ? d2 : d2 - 1.0;
            double fiddledDistance = getFiddledDistance(this.biomeZoomSeed, i8, i9, i10, d4, d5, d6);
            if (d3 > fiddledDistance) {
                i6 = i7;
                d3 = fiddledDistance;
            }
        }

        int i7x = (i6 & 4) == 0 ? i3 : i3 + 1;
        int i11 = (i6 & 2) == 0 ? i4 : i4 + 1;
        int i12 = (i6 & 1) == 0 ? i5 : i5 + 1;
        return this.noiseBiomeSource.getNoiseBiome(i7x, i11, i12);
    }

    public Holder<Biome> getNoiseBiomeAtPosition(double x, double y, double z) {
        int quartPosCoord = QuartPos.fromBlock(Mth.floor(x));
        int quartPosCoord1 = QuartPos.fromBlock(Mth.floor(y));
        int quartPosCoord2 = QuartPos.fromBlock(Mth.floor(z));
        return this.getNoiseBiomeAtQuart(quartPosCoord, quartPosCoord1, quartPosCoord2);
    }

    public Holder<Biome> getNoiseBiomeAtPosition(BlockPos pos) {
        int quartPosX = QuartPos.fromBlock(pos.getX());
        int quartPosY = QuartPos.fromBlock(pos.getY());
        int quartPosZ = QuartPos.fromBlock(pos.getZ());
        return this.getNoiseBiomeAtQuart(quartPosX, quartPosY, quartPosZ);
    }

    public Holder<Biome> getNoiseBiomeAtQuart(int x, int y, int z) {
        return this.noiseBiomeSource.getNoiseBiome(x, y, z);
    }

    private static double getFiddledDistance(long seed, int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        long l = LinearCongruentialGenerator.next(seed, x);
        l = LinearCongruentialGenerator.next(l, y);
        l = LinearCongruentialGenerator.next(l, z);
        l = LinearCongruentialGenerator.next(l, x);
        l = LinearCongruentialGenerator.next(l, y);
        l = LinearCongruentialGenerator.next(l, z);
        double fiddle = getFiddle(l);
        l = LinearCongruentialGenerator.next(l, seed);
        double fiddle1 = getFiddle(l);
        l = LinearCongruentialGenerator.next(l, seed);
        double fiddle2 = getFiddle(l);
        return Mth.square(zNoise + fiddle2) + Mth.square(yNoise + fiddle1) + Mth.square(xNoise + fiddle);
    }

    private static double getFiddle(long seed) {
        double d = Math.floorMod(seed >> 24, 1024) / 1024.0;
        return (d - 0.5) * 0.9;
    }

    public interface NoiseBiomeSource {
        Holder<Biome> getNoiseBiome(int x, int y, int z);
    }
}
