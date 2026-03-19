package cn.li.forge1201.platform;

import my_mod.IBlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class Forge1201BlockHelper implements IBlockHelper {

    @Override
    public void setBlock(Object level, int x, int y, int z, Object state) {
        if (level instanceof Level l && state instanceof BlockState s) {
            // 将 int 坐标包装为 1.20.1 的 BlockPos 对象
            l.setBlockAndUpdate(new BlockPos(x, y, z), s);
        }
    }

    @Override
    public Object getBlockEntity(Object level, int x, int y, int z) {
        if (level instanceof Level l) {
            // 获取 BlockEntity (1.20.1 中返回 BlockEntity 对象)
            return l.getBlockEntity(new BlockPos(x, y, z));
        }
        return null;
    }

    @Override
    public void destroyBlock(Object level, int x, int y, int z, boolean drop) {
        if (level instanceof Level l) {
            l.destroyBlock(new BlockPos(x, y, z), drop);
        }
    }
}
