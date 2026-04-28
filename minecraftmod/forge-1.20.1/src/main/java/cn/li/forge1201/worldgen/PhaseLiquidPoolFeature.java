package cn.li.forge1201.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Phase Liquid Pool Feature - generates imaginary phase liquid pools underground.
 *
 * Based on original AcademyCraft WorldGenPhaseLiq implementation.
 * Uses ellipsoid stacking algorithm to create natural-looking pools.
 */
public class PhaseLiquidPoolFeature extends Feature<NoneFeatureConfiguration> {

    private final BlockState phaseLiquidBlock;

    public PhaseLiquidPoolFeature(Codec<NoneFeatureConfiguration> codec, BlockState phaseLiquidBlock) {
        super(codec);
        this.phaseLiquidBlock = phaseLiquidBlock;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos pos = context.origin();
        RandomSource random = context.random();

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        // Find a non-air block as origin (search downward from chunk center)
        x -= 8;
        z -= 8;
        while (y > 5 && level.isEmptyBlock(new BlockPos(x, y, z))) {
            --y;
        }

        if (y <= 4) {
            return false;
        }

        y -= 4;

        // Buffer to store which blocks should be part of the pool
        // 16x16x8 = 2048 blocks
        boolean[] buffer = new boolean[2048];

        // Generate 4-7 ellipsoids and combine them
        int ellipsoidCount = random.nextInt(4) + 4;
        for (int i = 0; i < ellipsoidCount; ++i) {
            // Ellipsoid radii
            double radiusX = random.nextDouble() * 6.0D + 3.0D; // 3-9
            double radiusY = random.nextDouble() * 4.0D + 2.0D; // 2-6
            double radiusZ = random.nextDouble() * 6.0D + 3.0D; // 3-9

            // Ellipsoid center position (within the 16x16x8 buffer)
            double centerX = random.nextDouble() * (14.0D - radiusX) + 1.0D + radiusX / 2.0D;
            double centerY = random.nextDouble() * (4.0D - radiusY) + 2.0D + radiusY / 2.0D;
            double centerZ = random.nextDouble() * (14.0D - radiusZ) + 1.0D + radiusZ / 2.0D;

            // Fill ellipsoid
            for (int bx = 1; bx < 15; ++bx) {
                for (int bz = 1; bz < 15; ++bz) {
                    for (int by = 1; by < 7; ++by) {
                        // Calculate normalized distance from ellipsoid center
                        double dx = (bx - centerX) / (radiusX / 2.0D);
                        double dy = (by - centerY) / (radiusY / 2.0D);
                        double dz = (bz - centerZ) / (radiusZ / 2.0D);
                        double distSq = dx * dx + dy * dy + dz * dz;

                        // If inside ellipsoid (with threshold 0.6)
                        if (distSq < 0.6D) {
                            buffer[(bx * 16 + bz) * 8 + by] = true;
                        }
                    }
                }
            }
        }

        // Validate placement - check if location is suitable
        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                for (int by = 0; by < 8; ++by) {
                    // Check if this is an edge block (adjacent to pool interior)
                    boolean isEdge = !buffer[(bx * 16 + bz) * 8 + by] && (
                        (bx < 15 && buffer[((bx + 1) * 16 + bz) * 8 + by]) ||
                        (bx > 0 && buffer[((bx - 1) * 16 + bz) * 8 + by]) ||
                        (bz < 15 && buffer[(bx * 16 + bz + 1) * 8 + by]) ||
                        (bz > 0 && buffer[(bx * 16 + (bz - 1)) * 8 + by]) ||
                        (by < 7 && buffer[(bx * 16 + bz) * 8 + by + 1]) ||
                        (by > 0 && buffer[(bx * 16 + bz) * 8 + (by - 1)])
                    );

                    if (isEdge) {
                        BlockPos checkPos = new BlockPos(x + bx, y + by, z + bz);
                        BlockState state = level.getBlockState(checkPos);
                        boolean hasLiquid = !state.getFluidState().isEmpty();

                        // Above liquid depth (y >= 4): must not have existing liquid
                        if (by >= 4 && hasLiquid) {
                            return false;
                        }

                        // Below liquid depth (y < 4): must be solid or our phase liquid
                        if (by < 4 && !state.isSolidRender(level, checkPos) && !state.is(phaseLiquidBlock.getBlock())) {
                            return false;
                        }
                    }
                }
            }
        }

        // Place blocks
        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                for (int by = 0; by < 8; ++by) {
                    if (buffer[(bx * 16 + bz) * 8 + by]) {
                        BlockPos placePos = new BlockPos(x + bx, y + by, z + bz);
                        // Below y=4: place phase liquid, above: place air
                        BlockState toPlace = by >= 4 ? Blocks.AIR.defaultBlockState() : phaseLiquidBlock;
                        level.setBlock(placePos, toPlace, 2);
                    }
                }
            }
        }

        // Convert dirt to grass/mycelium above the pool
        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                for (int by = 4; by < 8; ++by) {
                    BlockPos checkPos = new BlockPos(x + bx, y + by - 1, z + bz);
                    BlockState belowState = level.getBlockState(checkPos);
                    BlockPos abovePos = new BlockPos(x + bx, y + by, z + bz);

                    if (buffer[(bx * 16 + bz) * 8 + by] &&
                        belowState.is(Blocks.DIRT) &&
                        level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, abovePos) > 0) {

                        // 1.20 removed direct surface builder access from biome generation settings.
                        // Keep the vanilla-like behavior by converting lit dirt to grass.
                        level.setBlock(checkPos, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    }
                }
            }
        }

        // Freeze water surface if cold biome
        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                BlockPos surfacePos = new BlockPos(x + bx, y + 4, z + bz);
                // Check if this position can freeze water
                if (level.getBiome(surfacePos).value().coldEnoughToSnow(surfacePos)) {
                    BlockState surfaceState = level.getBlockState(surfacePos);
                    if (surfaceState.is(phaseLiquidBlock.getBlock())) {
                        level.setBlock(surfacePos, Blocks.ICE.defaultBlockState(), 2);
                    }
                }
            }
        }

        return true;
    }
}
