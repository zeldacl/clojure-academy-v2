package my_mod;

public interface IBlockHelper {
    /** 
     * 在指定位置设置方块 
     * @param level 传入 Level/World 对象
     * @param state 传入 BlockState 对象
     */
    void setBlock(Object level, int x, int y, int z, Object state);

    /** 获取指定位置的 BlockEntity/TileEntity */
    Object getBlockEntity(Object level, int x, int y, int z);

    /** 破坏方块并决定是否掉落物品 */
    void destroyBlock(Object level, int x, int y, int z, boolean drop);
}
