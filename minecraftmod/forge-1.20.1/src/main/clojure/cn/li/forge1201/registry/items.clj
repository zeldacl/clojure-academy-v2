(ns cn.li.forge1201.registry.items
  "Item registration for Forge 1.20.1."
  (:require [cn.li.forge1201.registry.item-properties :as item-properties]
            [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.registry.metadata :as registry-metadata])
  (:import [net.minecraft.world.item BlockItem Item$Properties]
           [net.minecraftforge.registries DeferredRegister RegistryObject]))

(defn register-all-items!
  [{:keys [items-register]}]
  (doseq [item-id (registry-metadata/get-all-item-ids)]
    (let [registry-name (registry-metadata/get-item-registry-name item-id)
          item-spec (registry-metadata/get-item-spec item-id)
          registered-obj (.register ^DeferredRegister items-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (item-properties/create-standalone-item item-spec))))]
      (swap! registry-state/registered-items assoc item-id registered-obj)))
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (and (registry-metadata/should-create-block-item? block-id)
               (not (registry-metadata/fluid-block? block-id)))
      (let [registry-name (registry-metadata/get-block-registry-name block-id)
            block-registered (get @registry-state/registered-blocks block-id)
            registered-obj (.register ^DeferredRegister items-register registry-name
                                      (reify java.util.function.Supplier
                                        (get [_]
                                          (BlockItem. (.get ^RegistryObject block-registered)
                                                      (Item$Properties.)))))]
        (swap! registry-state/registered-items assoc (str block-id "-item") registered-obj)))))
