package net.minecraft.world.level.chunk.storage;

import com.google.common.annotations.VisibleForTesting;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.BitSet;

public class RegionBitmap {
    private final BitSet used = new BitSet();

    public void force(int sectorOffset, int sectorCount) {
        this.used.set(sectorOffset, sectorOffset + sectorCount);
    }

    public void free(int sectorOffset, int sectorCount) {
        this.used.clear(sectorOffset, sectorOffset + sectorCount);
    }

    public int allocate(int sectorCount) {
        int i = 0;

        while (true) {
            int i1 = this.used.nextClearBit(i);
            int i2 = this.used.nextSetBit(i1);
            if (i2 == -1 || i2 - i1 >= sectorCount) {
                this.force(i1, sectorCount);
                return i1;
            }

            i = i2;
        }
    }

    @VisibleForTesting
    public IntSet getUsed() {
        return this.used.stream().collect(IntArraySet::new, IntCollection::add, IntCollection::addAll);
    }
}
