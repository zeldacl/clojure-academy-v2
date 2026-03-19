package my_mod.api;

import java.util.function.Supplier;

public interface IRegistryHelper {
    // 抽象出创建基础方块和物品的方法
    Object createBasicBlock();
    Object createBasicItem();
}

// (ns com.example.mod.ac.init
//   (:require [com.example.mod.mcmod.registry :as reg]
//             [com.example.mod.platform :as p]))

// (defn init []
//   ;; 注册一个方块
//   (reg/register-block! "my_cool_block" 
//     #(p/create-basic-block)) ;; 这里 p/create-basic-block 会调用 IRegistryHelper
    
//   (println "AC Logic: Blocks declared!"))
