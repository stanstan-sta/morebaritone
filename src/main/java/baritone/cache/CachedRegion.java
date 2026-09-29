/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.cache;

import baritone.Baritone;
import baritone.api.cache.ICachedRegion;
import baritone.api.utils.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @author Brady
 * @since 8/3/2018
 */
public final class CachedRegion implements ICachedRegion {

    private static final byte CHUNK_NOT_PRESENT = 0;
    private static final byte CHUNK_PRESENT = 1;

    /**
     * V1 magic (original format). No blockTypesPresent index.
     */
    private static final int CACHED_REGION_MAGIC = 456022911;

    /**
     * V2 magic: adds per-chunk blockTypesPresent for fast {@code getLocationsOf()}.
     * Loaded V1 files will be upgraded on next save.
     */
    private static final int CACHED_REGION_MAGIC_V2 = 456022912;

    /**
     * All of the chunks in this region: A 32x32 array of them.
     */
    private final CachedChunk[][] chunks = new CachedChunk[32][32];

    /**
     * The region x coordinate
     */
    private final int x;

    /**
     * The region z coordinate
     */
    private final int z;

    private final DimensionType dimension;

    private final ResourceKey<Level> dimensionId;

    /**
     * Has this region been modified since its most recent load or save
     */
    private boolean hasUnsavedChanges;

    /**
     * Block name → BitSet of chunk positions (bit index = chunkX * 32 + chunkZ)
     * that contain at least one of this block. Rebuilt on load, updated on pack.
     */
    private final Map<String, BitSet> blockChunkIndex = new HashMap<>();

    CachedRegion(int x, int z, DimensionType dimension, ResourceKey<Level> dimensionId) {
        this.x = x;
        this.z = z;
        this.hasUnsavedChanges = false;
        this.dimension = dimension;
        this.dimensionId = dimensionId;
    }

    @Override
    public final BlockState getBlock(int x, int y, int z) {
        int adjY = y - dimension.minY();
        CachedChunk chunk = chunks[x >> 4][z >> 4];
        if (chunk != null) {
            return chunk.getBlock(x & 15, adjY, z & 15, dimension, dimensionId);
        }
        return null;
    }

    @Override
    public final boolean isCached(int x, int z) {
        return chunks[x >> 4][z >> 4] != null;
    }

    // Synchronized like the other region methods: the block index and chunk
    // table are mutated under the region lock by update/load/removeExpired
    // while lookup threads read them here.
    public final synchronized ArrayList<BlockPos> getLocationsOf(String block) {
        ArrayList<BlockPos> res = new ArrayList<>();
        BitSet index = blockChunkIndex.get(block);
        if (index == null || index.isEmpty()) {
            return res; // no chunk in this region has this block
        }
        for (int i = index.nextSetBit(0); i >= 0; i = index.nextSetBit(i + 1)) {
            int cx = i / 32;
            int cz = i % 32;
            CachedChunk chunk = this.chunks[cx][cz];
            if (chunk != null) {
                ArrayList<BlockPos> locs = chunk.getAbsoluteBlocks(block);
                if (locs != null) {
                    res.addAll(locs);
                }
            }
        }
        return res;
    }

    public final synchronized void updateCachedChunk(int chunkX, int chunkZ, CachedChunk chunk) {
        // Remove old chunk's block types from the index
        CachedChunk old = this.chunks[chunkX][chunkZ];
        if (old != null && old.getBlockTypesPresent() != null) {
            int bit = chunkX * 32 + chunkZ;
            for (String blockType : old.getBlockTypesPresent()) {
                BitSet bs = blockChunkIndex.get(blockType);
                if (bs != null) {
                    bs.clear(bit);
                    if (bs.isEmpty()) {
                        blockChunkIndex.remove(blockType);
                    }
                }
            }
        }
        // Add new chunk's block types to the index
        if (chunk != null && chunk.getBlockTypesPresent() != null) {
            int bit = chunkX * 32 + chunkZ;
            for (String blockType : chunk.getBlockTypesPresent()) {
                blockChunkIndex.computeIfAbsent(blockType, k -> new BitSet(1024)).set(bit);
            }
        }
        this.chunks[chunkX][chunkZ] = chunk;
        hasUnsavedChanges = true;
    }


    public synchronized final void save(String directory) {
        if (!hasUnsavedChanges) {
            return;
        }
        removeExpired();
        try {
            Path path = Paths.get(directory);
            if (!Files.exists(path)) {
                Files.createDirectories(path);

            }
            System.out.println("Saving region " + x + "," + z + " to disk " + path);
            Path regionFile = getRegionFile(path, this.x, this.z);
            if (!Files.exists(regionFile)) {
                Files.createFile(regionFile);
            }
            try (
                    FileOutputStream fileOut = new FileOutputStream(regionFile.toFile());
                    GZIPOutputStream gzipOut = new GZIPOutputStream(fileOut, 16384);
                    DataOutputStream out = new DataOutputStream(gzipOut)
            ) {
                // V1 magic: the block-type index is rebuilt at load from the
                // stored special locations, so files stay readable by other
                // Baritone builds sharing this cache directory. The V2 loader
                // path is kept for files already written in V2.
                out.writeInt(CACHED_REGION_MAGIC);
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        CachedChunk chunk = this.chunks[x][z];
                        if (chunk == null) {
                            out.write(CHUNK_NOT_PRESENT);
                        } else {
                            out.write(CHUNK_PRESENT);
                            byte[] chunkBytes = chunk.toByteArray();
                            out.write(chunkBytes);
                            // Messy, but fills the empty 0s that should be trailing to fill up the space.
                            out.write(new byte[chunk.sizeInBytes - chunkBytes.length]);
                        }
                    }
                }
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (chunks[x][z] != null) {
                            for (int i = 0; i < 256; i++) {
                                out.writeUTF(BlockUtils.blockToString(chunks[x][z].getOverview()[i].getBlock()));
                            }
                        }
                    }
                }
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (chunks[x][z] != null) {
                            Map<String, List<BlockPos>> locs = chunks[x][z].getRelativeBlocks();
                            out.writeShort(locs.entrySet().size());
                            for (Map.Entry<String, List<BlockPos>> entry : locs.entrySet()) {
                                out.writeUTF(entry.getKey());
                                out.writeShort(entry.getValue().size());
                                for (BlockPos pos : entry.getValue()) {
                                    out.writeByte((byte) (pos.getZ() << 4 | pos.getX()));
                                    out.writeInt(pos.getY() - dimension.minY());
                                }
                            }
                        }
                    }
                }
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (chunks[x][z] != null) {
                            out.writeLong(chunks[x][z].cacheTimestamp);
                        }
                    }
                }
            }
            hasUnsavedChanges = false;
            System.out.println("Saved region successfully");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public synchronized void load(String directory) {
        try {
            Path path = Paths.get(directory);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }

            Path regionFile = getRegionFile(path, this.x, this.z);
            if (!Files.exists(regionFile)) {
                return;
            }

            System.out.println("Loading region " + x + "," + z + " from disk " + path);
            long start = System.nanoTime() / 1000000L;

            try (
                    FileInputStream fileIn = new FileInputStream(regionFile.toFile());
                    GZIPInputStream gzipIn = new GZIPInputStream(fileIn, 32768);
                    DataInputStream in = new DataInputStream(gzipIn)
            ) {
                int magic = in.readInt();
                boolean isV2;
                if (magic == CACHED_REGION_MAGIC_V2) {
                    isV2 = true;
                } else if (magic == CACHED_REGION_MAGIC) {
                    isV2 = false;
                } else {
                    throw new IOException("Bad magic value " + magic);
                }

                boolean[][] present = new boolean[32][32];
                BitSet[][] bitSets = new BitSet[32][32];
                Map<String, List<BlockPos>>[][] location = new Map[32][32];
                BlockState[][][] overview = new BlockState[32][32][];
                long[][] cacheTimestamp = new long[32][32];
                Set<String>[][] blockTypes = new Set[32][32];

                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        int isChunkPresent = in.read();
                        switch (isChunkPresent) {
                            case CHUNK_PRESENT:
                                byte[] bytes = new byte[CachedChunk.sizeInBytes(CachedChunk.size(dimension.height()))];
                                in.readFully(bytes);
                                bitSets[x][z] = BitSet.valueOf(bytes);
                                location[x][z] = new HashMap<>();
                                overview[x][z] = new BlockState[256];
                                present[x][z] = true;
                                break;
                            case CHUNK_NOT_PRESENT:
                                break;
                            default:
                                throw new IOException("Malformed stream");
                        }
                    }
                }
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (present[x][z]) {
                            for (int i = 0; i < 256; i++) {
                                overview[x][z][i] = BlockUtils.stringToBlockRequired(in.readUTF()).defaultBlockState();
                            }
                        }
                    }
                }
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (present[x][z]) {
                            int numSpecialBlockTypes = in.readShort() & 0xffff;
                            for (int i = 0; i < numSpecialBlockTypes; i++) {
                                String blockName = in.readUTF();
                                BlockUtils.stringToBlockRequired(blockName);
                                List<BlockPos> locs = new ArrayList<>();
                                location[x][z].put(blockName, locs);
                                int numLocations = in.readShort() & 0xffff;
                                if (numLocations == 0) {
                                    numLocations = 65536;
                                }
                                for (int j = 0; j < numLocations; j++) {
                                    byte xz = in.readByte();
                                    int X = xz & 0x0f;
                                    int Z = (xz >>> 4) & 0x0f;
                                    int Y = in.readInt();
                                    locs.add(new BlockPos(X, Y + dimension.minY(), Z));
                                }
                            }
                        }
                    }
                }
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (present[x][z]) {
                            cacheTimestamp[x][z] = in.readLong();
                        }
                    }
                }
                // ── V2: read blockTypesPresent ───────────────────────────────
                if (isV2) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            if (present[x][z]) {
                                int numTypes = in.readShort() & 0xffff;
                                Set<String> types = new HashSet<>(numTypes);
                                for (int i = 0; i < numTypes; i++) {
                                    types.add(in.readUTF());
                                }
                                blockTypes[x][z] = types;
                            } else {
                                blockTypes[x][z] = Collections.emptySet();
                            }
                        }
                    }
                }

                // only if the entire file was uncorrupted do we actually set the chunks
                blockChunkIndex.clear();
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (present[x][z]) {
                            int regionX = this.x;
                            int regionZ = this.z;
                            int chunkX = x + 32 * regionX;
                            int chunkZ = z + 32 * regionZ;
                            Set<String> types = isV2 ? blockTypes[x][z] : Collections.emptySet();
                            CachedChunk c = new CachedChunk(chunkX, chunkZ, dimension.height(),
                                    bitSets[x][z], overview[x][z], location[x][z],
                                    types, cacheTimestamp[x][z]);
                            this.chunks[x][z] = c;
                            // Build index from all block types present
                            if (!types.isEmpty()) {
                                int bit = x * 32 + z;
                                for (String bt : types) {
                                    blockChunkIndex.computeIfAbsent(bt, k -> new BitSet(1024)).set(bit);
                                }
                            }
                        }
                    }
                }
                // For V1 files, build index from specialBlockLocations (degraded but still useful)
                if (!isV2) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            if (present[x][z]) {
                                int bit = x * 32 + z;
                                for (String bt : location[x][z].keySet()) {
                                    blockChunkIndex.computeIfAbsent(bt, k -> new BitSet(1024)).set(bit);
                                }
                            }
                        }
                    }
                }
            }
            removeExpired();
            hasUnsavedChanges = false;
            long end = System.nanoTime() / 1000000L;
            System.out.println("Loaded region successfully in " + (end - start) + "ms");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public synchronized final void removeExpired() {
        long expiry = Baritone.settings().cachedChunksExpirySeconds.value;
        if (expiry < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long oldestAcceptableAge = now - expiry * 1000L;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (this.chunks[x][z] != null && this.chunks[x][z].cacheTimestamp < oldestAcceptableAge) {
                    System.out.println("Removing chunk " + (x + 32 * this.x) + "," + (z + 32 * this.z) + " because it was cached " + (now - this.chunks[x][z].cacheTimestamp) / 1000L + " seconds ago, and max age is " + expiry);
                    // Remove from index
                    CachedChunk old = this.chunks[x][z];
                    if (old.getBlockTypesPresent() != null) {
                        int bit = x * 32 + z;
                        for (String bt : old.getBlockTypesPresent()) {
                            BitSet bs = blockChunkIndex.get(bt);
                            if (bs != null) {
                                bs.clear(bit);
                                if (bs.isEmpty()) {
                                    blockChunkIndex.remove(bt);
                                }
                            }
                        }
                    }
                    this.chunks[x][z] = null;
                }
            }
        }
    }

    public synchronized final CachedChunk mostRecentlyModified() {
        CachedChunk recent = null;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (this.chunks[x][z] == null) {
                    continue;
                }
                if (recent == null || this.chunks[x][z].cacheTimestamp > recent.cacheTimestamp) {
                    recent = this.chunks[x][z];
                }
            }
        }
        return recent;
    }

    /**
     * @return The region x coordinate
     */
    @Override
    public final int getX() {
        return this.x;
    }

    /**
     * @return The region z coordinate
     */
    @Override
    public final int getZ() {
        return this.z;
    }

    private static Path getRegionFile(Path cacheDir, int regionX, int regionZ) {
        return Paths.get(cacheDir.toString(), "r." + regionX + "." + regionZ + ".bcr");
    }
}