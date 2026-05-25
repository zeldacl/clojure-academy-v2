package cn.li.mc1201.worldgen;

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
 * Shared implementation for a small configurable underground pool feature.
 */
public class ConfigurablePoolFeature extends Feature<NoneFeatureConfiguration> {

    private final BlockState fillBlock;

    public ConfigurablePoolFeature(Codec<NoneFeatureConfiguration> codec, BlockState fillBlock) {
        super(codec);
        this.fillBlock = fillBlock;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos pos = context.origin();
        RandomSource random = context.random();

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        x -= 8;
        z -= 8;
        while (y > 5 && level.isEmptyBlock(new BlockPos(x, y, z))) {
            --y;
        }

        if (y <= 4) {
            return false;
        }

        y -= 4;

        boolean[] buffer = new boolean[2048];

        int ellipsoidCount = random.nextInt(4) + 4;
        for (int i = 0; i < ellipsoidCount; ++i) {
            double radiusX = random.nextDouble() * 6.0D + 3.0D;
            double radiusY = random.nextDouble() * 4.0D + 2.0D;
            double radiusZ = random.nextDouble() * 6.0D + 3.0D;

            double centerX = random.nextDouble() * (14.0D - radiusX) + 1.0D + radiusX / 2.0D;
            double centerY = random.nextDouble() * (4.0D - radiusY) + 2.0D + radiusY / 2.0D;
            double centerZ = random.nextDouble() * (14.0D - radiusZ) + 1.0D + radiusZ / 2.0D;

            for (int bx = 1; bx < 15; ++bx) {
                for (int bz = 1; bz < 15; ++bz) {
                    for (int by = 1; by < 7; ++by) {
                        double dx = (bx - centerX) / (radiusX / 2.0D);
                        double dy = (by - centerY) / (radiusY / 2.0D);
                        double dz = (bz - centerZ) / (radiusZ / 2.0D);
                        double distSq = dx * dx + dy * dy + dz * dz;

                        if (distSq < 0.6D) {
                            buffer[(bx * 16 + bz) * 8 + by] = true;
                        }
                    }
                }
            }
        }

        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                for (int by = 0; by < 8; ++by) {
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

                        if (by >= 4 && hasLiquid) {
                            return false;
                        }

                        if (by < 4 && !state.isSolidRender(level, checkPos) && !state.is(fillBlock.getBlock())) {
                            return false;
                        }
                    }
                }
            }
        }

        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                for (int by = 0; by < 8; ++by) {
                    if (buffer[(bx * 16 + bz) * 8 + by]) {
                        BlockPos placePos = new BlockPos(x + bx, y + by, z + bz);
                        BlockState toPlace = by >= 4 ? Blocks.AIR.defaultBlockState() : fillBlock;
                        level.setBlock(placePos, toPlace, 2);
                    }
                }
            }
        }

        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                for (int by = 4; by < 8; ++by) {
                    BlockPos checkPos = new BlockPos(x + bx, y + by - 1, z + bz);
                    BlockState belowState = level.getBlockState(checkPos);
                    BlockPos abovePos = new BlockPos(x + bx, y + by, z + bz);

                    if (buffer[(bx * 16 + bz) * 8 + by] &&
                        belowState.is(Blocks.DIRT) &&
                        level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, abovePos) > 0) {
                        level.setBlock(checkPos, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    }
                }
            }
        }

        for (int bx = 0; bx < 16; ++bx) {
            for (int bz = 0; bz < 16; ++bz) {
                BlockPos surfacePos = new BlockPos(x + bx, y + 4, z + bz);
                if (level.getBiome(surfacePos).value().coldEnoughToSnow(surfacePos)) {
                    BlockState surfaceState = level.getBlockState(surfacePos);
                    if (surfaceState.is(fillBlock.getBlock())) {
                        level.setBlock(surfacePos, Blocks.ICE.defaultBlockState(), 2);
                    }
                }
            }
        }

        return true;
    }
}
