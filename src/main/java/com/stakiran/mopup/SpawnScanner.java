package com.stakiran.mopup;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.border.WorldBorder;

// ゲームの規模の推定: ボーダー内・y>=minY で敵モブが湧ける場所を数える
public class SpawnScanner {

    // Sky light at or above this is "outdoor". At midnight sky light is reduced by 11,
    // so only these positions fail the light test; they also fail the sky light check
    // (skyLight > random(32)) more often. Result: ~1/4 spawn chance compared to caves.
    private static final int OUTDOOR_SKY_LIGHT = 12;

    // Indoor positions spawn ~4x more often than outdoor ones
    private static final int INDOOR_WEIGHT = 4;

    // maxSurfaceY: highest WORLD_SURFACE top (feet Y when standing on the highest block) inside the border
    public record Result(int outdoor, int indoor, int maxSurfaceY) {
        public int scale() {
            return (outdoor + indoor * INDOOR_WEIGHT) / 100;
        }
    }

    public static Result scan(ServerWorld world, int minY) {
        WorldBorder border = world.getWorldBorder();
        int minX = (int) Math.floor(border.getBoundWest());
        int maxX = (int) Math.floor(border.getBoundEast());
        int minZ = (int) Math.floor(border.getBoundNorth());
        int maxZ = (int) Math.floor(border.getBoundSouth());

        int outdoor = 0;
        int indoor = 0;
        int maxSurfaceY = minY;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable below = new BlockPos.Mutable();
        BlockPos.Mutable above = new BlockPos.Mutable();

        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                // Nothing to spawn on above the highest non-air block
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                maxSurfaceY = Math.max(maxSurfaceY, topY);
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
        return new Result(outdoor, indoor, maxSurfaceY);
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
