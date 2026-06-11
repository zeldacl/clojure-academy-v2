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
              (if-let [logo-item (registry-state/get-registered-item "logo")]
                (net.minecraft.world.item.ItemStack. logo-item)
                (.getDefaultInstance Items/BARRIER))
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
                    (.accept output (net.minecraft.world.item.ItemStack. ^ItemLike item-obj))
                    ;; Add filled / charged variants for stateful items
                    ;; so players can spawn every variant from creative.
                    (when (= (:type entry) :item)
                      (when-let [spec (registry-metadata/get-item-spec item-id)]
                        (let [props (:properties spec)]
                          ;; Energy items: fully-charged variant
                          (when (true? (:energy-item? props))
                            (let [capacity (double (or (:energy-capacity props) 0.0))]
                              (when (pos? capacity)
                                (try
                                  (let [full-stack (net.minecraft.world.item.ItemStack. ^ItemLike item-obj)
                                        tag (.getOrCreateTag full-stack)
                                        bandwidth (double (or (:energy-bandwidth props) 0.0))
                                        battery-type (or (:battery-type props) item-id)]
                                    (.putDouble tag "energy" capacity)
                                    (.putDouble tag "maxEnergy" capacity)
                                    (.putDouble tag "bandwidth" bandwidth)
                                    (.putString tag "batteryType" battery-type)
                                    (.accept output full-stack))
                                  (catch Exception _ nil)))))
                          ;; Generic filled-variant items (e.g. matter_unit)
                          (when-let [variant (:filled-variant props)]
                            (try
                              (let [variant-stack (net.minecraft.world.item.ItemStack. ^ItemLike item-obj)
                                    tag (.getOrCreateTag variant-stack)]
                                (doseq [[k v] (:nbt variant)]
                                  (cond
                                    (string? v) (.putString tag k (str v))
                                    (number? v) (.putDouble tag k (double v))
                                    (instance? Boolean v) (.putBoolean tag k v)
                                    :else nil))
                                (when-let [dmg (:damage variant)]
                                  (.setDamageValue variant-stack (int dmg)))
                                (.accept output variant-stack))
                              (catch Exception _ nil))))))))))]
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
