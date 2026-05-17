(ns cn.li.forge1201.registry.creative-tab
  "Forge creative tab registration extracted from the mod entrypoint."
  (:require [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.protocol.metadata :as registry-metadata])
  (:import [net.minecraft.network.chat Component]
           [net.minecraft.world.item CreativeModeTab Items]
           [net.minecraft.world.level ItemLike]
           [net.minecraftforge.registries DeferredRegister]))

(defn build-creative-tab
  "Build the mod creative tab. displayItems callback runs lazily when the tab opens,
  so registry-state atoms are fully populated by then."
  [mod-id]
  (let [icon-supplier
        (reify java.util.function.Supplier
          (get [_]
            (try
              (.getDefaultInstance Items/BARRIER)
              (catch Exception _
                net.minecraft.world.item.ItemStack/EMPTY))))
        display-items
        (reify net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator
          (accept [_ _params output]
            (doseq [entry (registry-metadata/get-all-creative-tab-entries)]
              (when (some? (:tab entry))
                (let [item-id (:id entry)
                      item-obj (if (= (:type entry) :block-item)
                         (registry-state/get-registered-block-item item-id)
                         (registry-state/get-registered-item item-id))]
                  (when item-obj
                    (.accept output (net.minecraft.world.item.ItemStack. ^ItemLike item-obj))))))))]
    (-> (CreativeModeTab/builder)
        (.title (Component/translatable (str "itemGroup." mod-id ".items")))
        (.icon icon-supplier)
        (.displayItems display-items)
        (.build))))

(defn register-creative-tab!
  [^DeferredRegister creative-tabs-register mod-id]
  (.register creative-tabs-register
             "items"
             (reify java.util.function.Supplier
               (get [_]
                 (build-creative-tab mod-id)))))
