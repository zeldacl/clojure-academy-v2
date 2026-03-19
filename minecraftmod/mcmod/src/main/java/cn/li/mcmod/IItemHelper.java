package cn.li.mcmod;

public interface IItemHelper {
    /** 
     * 获取物品堆栈中的长整型数据 
     * 1.20.1 实现：读取 NBT
     * 1.21 实现：读取 Data Component
     */
    long getLong(Object stack, String key);

    /** 设置物品堆栈中的长整型数据 */
    void setLong(Object stack, String key, long value);

    /** 检查两个物品是否属于同一种类（忽略数量和数据） */
    boolean isSameItem(Object stackA, Object stackB);

    /** 获取堆栈数量 */
    int getCount(Object stack);
}
