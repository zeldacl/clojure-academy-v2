package cn.li.forge1201.platform;

// import my_mod.registry; // 引用 clojure 命名空间
import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collection;
import java.util.Map;

public class Forge1201Registry {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "mymod");
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "mymod");

    public static void init(IEventBus bus) {
        // 1. 动态加载 Clojure 命名空间
        // RT.loadResourceScript("com/example/mod/mcmod/registry.clj");

        // 2. 获取 Clojure 中定义的 get-all-blocks 函数 Var
        Var getBlocksVar = RT.var("com.example.mod.mcmod.registry", "get-all-blocks");
        
        // 3. 调用并获取列表 (Clojure 的 PersistentVector 在 Java 中实现了 Collection)
        Collection<Map<String, Object>> blockList = (Collection<Map<String, Object>>) getBlocksVar.invoke();

        for (Map<String, Object> entry : blockList) {
            String id = (String) entry.get("id");
            // Clojure 的匿名函数在 Java 中实现了 Supplier (通过 Clojurephant 桥接或 IFn)
            IFn cljSupplier = (IFn) entry.get("supplier");
            
            // 注册方块
            RegistryObject<Block> blockReg = BLOCKS.register(id, () -> (Block) cljSupplier.invoke());
            
            // 自动注册对应的 BlockItem
            ITEMS.register(id, () -> new BlockItem(blockReg.get(), new Item.Properties()));
        }

        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
