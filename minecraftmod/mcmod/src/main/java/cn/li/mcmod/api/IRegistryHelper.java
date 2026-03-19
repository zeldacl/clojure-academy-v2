package cn.li.mcmod.api;

import java.util.function.Supplier;

public interface IRegistryHelper {
    // 抽象出创建基础方块和物品的方法
    Object createBasicBlock();
    Object createBasicItem();
}
