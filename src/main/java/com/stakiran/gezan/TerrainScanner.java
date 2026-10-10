package com.stakiran.gezan;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

// ボーダー内の地形を一度に走査して、最高点（葉を除く）とゲームの規模を求める
// 規模の計算式は mopup と同じ: y>=minY で敵モブが湧ける場所を数える
public class TerrainScanner {

    // Sky light at or above this is "outdoor". At midnight sky light is reduced by 11,
    // so only these positions fail the light test; they also fail the sky light check
    // (skyLight > random(32)) more often. Result: ~1/4 spawn chance compared to caves.
    private static final int OUTDOOR_SKY_LIGHT = 12;

    // Indoor positions spawn ~4x more often than outdoor ones
    private static final int INDOOR_WEIGHT = 4;

    // Grid step for estimateHighestY. Terrain noise is interpolated over 4x4 cells horizontally,
    // so sampling every 4 blocks catches the cell corners where the extremes are.
    private static final int ESTIMATE_STEP = 4;

    // highest: the highest block (not leaves, with a collision shape) inside the border.
    // Null only if the border contains no such block at all.
    public record Result(int outdoor, int indoor, BlockPos highest) {
        public int scale() {
            return (outdoor + indoor * INDOOR_WEIGHT) / 100;
        }
    }

    // Cheap estimate of the highest terrain Y in a size x size square around the center, taken from the
    // chunk generator's noise (no chunk is loaded or generated). Trees are not included, so the real
    // highest point found by scan() is normally at least this high. Returns bottomY - 1 if nothing was sampled.
    public static int estimateHighestY(ServerWorld world, double centerX, double centerZ, int size) {
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        int minX = (int) Math.ceil(centerX - size / 2.0);
        int maxX = (int) Math.floor(centerX + size / 2.0);
        int minZ = (int) Math.ceil(centerZ - size / 2.0);
        int maxZ = (int) Math.floor(centerZ + size / 2.0);

        int highest = world.getBottomY() - 1;
        for (int x = minX; x < maxX; x += ESTIMATE_STEP) {
            for (int z = minZ; z < maxZ; z += ESTIMATE_STEP) {
                // getHeight returns the Y of the first air block above the surface, like getTopY
                int y = generator.getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig) - 1;
                if (y > highest) highest = y;
            }
        }
        return highest;
    }

    // Loads (generates if needed) every chunk inside the border. Blocking, so call it only once
    // the location has been decided.
    public static void loadChunks(ServerWorld world) {
        WorldBorder border = world.getWorldBorder();
        int minChunkX = ChunkSectionPos.getSectionCoord((int) Math.ceil(border.getBoundWest()));
        int maxChunkX = ChunkSectionPos.getSectionCoord((int) Math.floor(border.getBoundEast()) - 1);
        int minChunkZ = ChunkSectionPos.getSectionCoord((int) Math.ceil(border.getBoundNorth()));
        int maxChunkZ = ChunkSectionPos.getSectionCoord((int) Math.floor(border.getBoundSouth()) - 1);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunk(cx, cz);
            }
        }
    }

    // Scans every column inside the border. Chunks are loaded as needed, so this also works right
    // after the border was moved somewhere the players have not been yet.
    public static Result scan(ServerWorld world, int minY) {
        // Only columns that lie entirely inside the border (bounds are at .5 when centered on a player).
        // A bed in a column that straddles the border cannot be respawned at: vanilla requires the
        // respawn bounding box to be fully inside the border.
        WorldBorder border = world.getWorldBorder();
        int minX = (int) Math.ceil(border.getBoundWest());
        int maxX = (int) Math.floor(border.getBoundEast());
        int minZ = (int) Math.ceil(border.getBoundNorth());
        int maxZ = (int) Math.floor(border.getBoundSouth());

        int outdoor = 0;
        int indoor = 0;
        BlockPos highest = null;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable below = new BlockPos.Mutable();
        BlockPos.Mutable above = new BlockPos.Mutable();

        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                // Y of the first air block above the highest non-air block.
                // World.getTopY would return bottomY for a chunk that is not loaded yet,
                // so go through getChunk, which loads (and generates) the chunk if needed.
                Chunk chunk = world.getChunk(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z));
                int topY = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) + 1;

                // Walk down past leaves, plants, water etc. to the highest block one can stand on
                int standY = findStandableY(world, x, topY - 1, z, world.getBottomY(), pos);
                if (standY >= world.getBottomY() && (highest == null || standY > highest.getY())) {
                    highest = new BlockPos(x, standY, z);
                }

                for (int y = minY; y <= topY; y++) {
                    pos.set(x, y, z);
                    if (!isSpawnable(world, pos, below, above)) continue;

                    if (world.getLightLevel(LightType.SKY, pos) >= OUTDOOR_SKY_LIGHT) {
                        outdoor++;
                    } else {
                        indoor++;
                    }
                }
            }
        }
        return new Result(outdoor, indoor, highest);
    }

    // Highest y in the column (starting at startY, going down) whose block has a collision shape
    // and is not leaves. Returns bottomY - 1 if there is none.
    private static int findStandableY(ServerWorld world, int x, int startY, int z, int bottomY, BlockPos.Mutable pos) {
        for (int y = startY; y >= bottomY; y--) {
            pos.set(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.isIn(BlockTags.LEAVES)) continue;
            if (state.getCollisionShape(world, pos).isEmpty()) continue;
            return y;
        }
        return bottomY - 1;
    }

    // Same conditions as a zombie (2 blocks tall) spawning, except light from the sky
    private static boolean isSpawnable(ServerWorld world, BlockPos pos, BlockPos.Mutable below, BlockPos.Mutable above) {
        below.set(pos.getX(), pos.getY() - 1, pos.getZ());
        BlockState floor = world.getBlockState(below);
        if (!floor.allowsSpawning(world, below, EntityType.ZOMBIE)) return false;

        BlockState feet = world.getBlockState(pos);
        if (!SpawnHelper.isClearForSpawn(world, pos, feet, feet.getFluidState(), EntityType.ZOMBIE)) return false;

        above.set(pos.getX(), pos.getY() + 1, pos.getZ());
        BlockState head = world.getBlockState(above);
        if (!SpawnHelper.isClearForSpawn(world, above, head, head.getFluidState(), EntityType.ZOMBIE)) return false;

        return world.getLightLevel(LightType.BLOCK, pos) == 0;
    }
}
