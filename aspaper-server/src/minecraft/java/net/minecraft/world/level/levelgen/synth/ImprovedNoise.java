package net.minecraft.world.level.levelgen.synth;

import com.google.common.annotations.VisibleForTesting;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class ImprovedNoise {
    private static final float SHIFT_UP_EPSILON = 1.0E-7F;
    private final byte[] p;
    public final double xo;
    public final double yo;
    public final double zo;

    public ImprovedNoise(RandomSource random) {
        this.xo = random.nextDouble() * 256.0;
        this.yo = random.nextDouble() * 256.0;
        this.zo = random.nextDouble() * 256.0;
        this.p = new byte[256];

        for (int i = 0; i < 256; i++) {
            this.p[i] = (byte)i;
        }

        for (int i = 0; i < 256; i++) {
            int randomInt = random.nextInt(256 - i);
            byte b = this.p[i];
            this.p[i] = this.p[i + randomInt];
            this.p[i + randomInt] = b;
        }
    }

    public double noise(double x, double y, double z) {
        return this.noise(x, y, z, 0.0, 0.0);
    }

    @Deprecated
    public double noise(double x, double y, double z, double yScale, double yMax) {
        double d = x + this.xo;
        double d1 = y + this.yo;
        double d2 = z + this.zo;
        int floor = Mth.floor(d);
        int floor1 = Mth.floor(d1);
        int floor2 = Mth.floor(d2);
        double d3 = d - floor;
        double d4 = d1 - floor1;
        double d5 = d2 - floor2;
        double d7;
        if (yScale != 0.0) {
            double d6;
            if (yMax >= 0.0 && yMax < d4) {
                d6 = yMax;
            } else {
                d6 = d4;
            }

            d7 = Mth.floor(d6 / yScale + 1.0E-7F) * yScale;
        } else {
            d7 = 0.0;
        }

        return this.sampleAndLerp(floor, floor1, floor2, d3, d4 - d7, d5, d4);
    }

    public double noiseWithDerivative(double x, double y, double z, double[] values) {
        double d = x + this.xo;
        double d1 = y + this.yo;
        double d2 = z + this.zo;
        int floor = Mth.floor(d);
        int floor1 = Mth.floor(d1);
        int floor2 = Mth.floor(d2);
        double d3 = d - floor;
        double d4 = d1 - floor1;
        double d5 = d2 - floor2;
        return this.sampleWithDerivative(floor, floor1, floor2, d3, d4, d5, values);
    }

    private static double gradDot(int gradIndex, double xFactor, double yFactor, double zFactor) {
        return SimplexNoise.dot(SimplexNoise.GRADIENT[gradIndex & 15], xFactor, yFactor, zFactor);
    }

    private int p(int index) {
        return this.p[index & 0xFF] & 0xFF;
    }

    private double sampleAndLerp(int gridX, int gridY, int gridZ, double deltaX, double weirdDeltaY, double deltaZ, double deltaY) {
        int i = this.p(gridX);
        int i1 = this.p(gridX + 1);
        int i2 = this.p(i + gridY);
        int i3 = this.p(i + gridY + 1);
        int i4 = this.p(i1 + gridY);
        int i5 = this.p(i1 + gridY + 1);
        double d = gradDot(this.p(i2 + gridZ), deltaX, weirdDeltaY, deltaZ);
        double d1 = gradDot(this.p(i4 + gridZ), deltaX - 1.0, weirdDeltaY, deltaZ);
        double d2 = gradDot(this.p(i3 + gridZ), deltaX, weirdDeltaY - 1.0, deltaZ);
        double d3 = gradDot(this.p(i5 + gridZ), deltaX - 1.0, weirdDeltaY - 1.0, deltaZ);
        double d4 = gradDot(this.p(i2 + gridZ + 1), deltaX, weirdDeltaY, deltaZ - 1.0);
        double d5 = gradDot(this.p(i4 + gridZ + 1), deltaX - 1.0, weirdDeltaY, deltaZ - 1.0);
        double d6 = gradDot(this.p(i3 + gridZ + 1), deltaX, weirdDeltaY - 1.0, deltaZ - 1.0);
        double d7 = gradDot(this.p(i5 + gridZ + 1), deltaX - 1.0, weirdDeltaY - 1.0, deltaZ - 1.0);
        double d8 = Mth.smoothstep(deltaX);
        double d9 = Mth.smoothstep(deltaY);
        double d10 = Mth.smoothstep(deltaZ);
        return Mth.lerp3(d8, d9, d10, d, d1, d2, d3, d4, d5, d6, d7);
    }

    private double sampleWithDerivative(int gridX, int gridY, int gridZ, double deltaX, double deltaY, double deltaZ, double[] noiseValues) {
        int i = this.p(gridX);
        int i1 = this.p(gridX + 1);
        int i2 = this.p(i + gridY);
        int i3 = this.p(i + gridY + 1);
        int i4 = this.p(i1 + gridY);
        int i5 = this.p(i1 + gridY + 1);
        int i6 = this.p(i2 + gridZ);
        int i7 = this.p(i4 + gridZ);
        int i8 = this.p(i3 + gridZ);
        int i9 = this.p(i5 + gridZ);
        int i10 = this.p(i2 + gridZ + 1);
        int i11 = this.p(i4 + gridZ + 1);
        int i12 = this.p(i3 + gridZ + 1);
        int i13 = this.p(i5 + gridZ + 1);
        int[] ints = SimplexNoise.GRADIENT[i6 & 15];
        int[] ints1 = SimplexNoise.GRADIENT[i7 & 15];
        int[] ints2 = SimplexNoise.GRADIENT[i8 & 15];
        int[] ints3 = SimplexNoise.GRADIENT[i9 & 15];
        int[] ints4 = SimplexNoise.GRADIENT[i10 & 15];
        int[] ints5 = SimplexNoise.GRADIENT[i11 & 15];
        int[] ints6 = SimplexNoise.GRADIENT[i12 & 15];
        int[] ints7 = SimplexNoise.GRADIENT[i13 & 15];
        double d = SimplexNoise.dot(ints, deltaX, deltaY, deltaZ);
        double d1 = SimplexNoise.dot(ints1, deltaX - 1.0, deltaY, deltaZ);
        double d2 = SimplexNoise.dot(ints2, deltaX, deltaY - 1.0, deltaZ);
        double d3 = SimplexNoise.dot(ints3, deltaX - 1.0, deltaY - 1.0, deltaZ);
        double d4 = SimplexNoise.dot(ints4, deltaX, deltaY, deltaZ - 1.0);
        double d5 = SimplexNoise.dot(ints5, deltaX - 1.0, deltaY, deltaZ - 1.0);
        double d6 = SimplexNoise.dot(ints6, deltaX, deltaY - 1.0, deltaZ - 1.0);
        double d7 = SimplexNoise.dot(ints7, deltaX - 1.0, deltaY - 1.0, deltaZ - 1.0);
        double d8 = Mth.smoothstep(deltaX);
        double d9 = Mth.smoothstep(deltaY);
        double d10 = Mth.smoothstep(deltaZ);
        double d11 = Mth.lerp3(d8, d9, d10, ints[0], ints1[0], ints2[0], ints3[0], ints4[0], ints5[0], ints6[0], ints7[0]);
        double d12 = Mth.lerp3(d8, d9, d10, ints[1], ints1[1], ints2[1], ints3[1], ints4[1], ints5[1], ints6[1], ints7[1]);
        double d13 = Mth.lerp3(d8, d9, d10, ints[2], ints1[2], ints2[2], ints3[2], ints4[2], ints5[2], ints6[2], ints7[2]);
        double d14 = Mth.lerp2(d9, d10, d1 - d, d3 - d2, d5 - d4, d7 - d6);
        double d15 = Mth.lerp2(d10, d8, d2 - d, d6 - d4, d3 - d1, d7 - d5);
        double d16 = Mth.lerp2(d8, d9, d4 - d, d5 - d1, d6 - d2, d7 - d3);
        double d17 = Mth.smoothstepDerivative(deltaX);
        double d18 = Mth.smoothstepDerivative(deltaY);
        double d19 = Mth.smoothstepDerivative(deltaZ);
        double d20 = d11 + d17 * d14;
        double d21 = d12 + d18 * d15;
        double d22 = d13 + d19 * d16;
        noiseValues[0] += d20;
        noiseValues[1] += d21;
        noiseValues[2] += d22;
        return Mth.lerp3(d8, d9, d10, d, d1, d2, d3, d4, d5, d6, d7);
    }

    @VisibleForTesting
    public void parityConfigString(StringBuilder builder) {
        NoiseUtils.parityNoiseOctaveConfigString(builder, this.xo, this.yo, this.zo, this.p);
    }
}
