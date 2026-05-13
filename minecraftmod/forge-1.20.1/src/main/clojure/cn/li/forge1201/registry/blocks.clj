(ns cn.li.forge1201.registry.blocks
  "Block and block-entity registration for Forge 1.20.1."
  (:require [cn.li.forge1201.integration.bootstrap :as bootstrap]
            [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.registry.metadata :as registry-metadata])
  (:import [net.minecraftforge.registries DeferredRegister RegistryObject]))

(defn- has-block-state-properties?
  [block-id]
  (registry-metadata/has-block-state-properties? block-id))

(defn register-scripted-tile-hooks!
  []
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (when-let [spec (registry-metadata/get-tile-spec tile-id)]
      (let [tick-fn (:tick-fn spec)
            read-nbt-fn (:read-nbt-fn spec)
            write-nbt-fn (:write-nbt-fn spec)
            tile-kind (:tile-kind spec)]
        (when (or tick-fn read-nbt-fn write-nbt-fn tile-kind)
          (tile-logic/register-tile-logic! tile-id
                                           {:tile-kind tile-kind
                                            :tick-fn tick-fn
                                            :read-nbt-fn read-nbt-fn
                                            :write-nbt-fn write-nbt-fn}))))))

(defn register-all-blocks!
  [{:keys [blocks-register registered-fluids-source base-properties carrier-properties]}]
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (let [registry-name (registry-metadata/get-block-registry-name block-id)
          fluid-id (registry-metadata/get-fluid-id-for-block block-id)
          needs-dynamic-properties? (has-block-state-properties? block-id)
          has-be? (registry-metadata/has-block-entity? block-id)
          tile-id (when has-be?
                    (or (registry-metadata/get-block-tile-id block-id) block-id))
          registered-obj (.register ^DeferredRegister blocks-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (let [get-props (requiring-resolve 'cn.li.mc1201.block.blockstate-properties/get-all-properties)]
                                          (cond
                                            fluid-id
                                            (when-let [fluid-source-ro (get @registered-fluids-source fluid-id)]
                                              (bootstrap/create-liquid-block
                                                (reify java.util.function.Supplier
                                                  (get [_]
                                                    (.get ^RegistryObject fluid-source-ro)))))
                                            (and needs-dynamic-properties? has-be?)
                                            (let [props (get-props block-id)]
                                              (bootstrap/create-carrier-scripted-dynamic-block block-id tile-id props base-properties))
                                            needs-dynamic-properties?
                                            (let [props (get-props block-id)]
                                              (bootstrap/create-dynamic-state-block block-id props base-properties))
                                            has-be?
                                            (bootstrap/create-carrier-scripted-block block-id tile-id carrier-properties)
                                            :else
                                            (bootstrap/create-plain-block base-properties))))))]
      (swap! registry-state/registered-blocks assoc block-id registered-obj))))

(defn register-block-entities!
  [{:keys [block-entities-register]}]
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (let [registry-name (registry-metadata/get-tile-registry-name tile-id)
          block-ids (registry-metadata/get-tile-block-ids tile-id)
          ros (keep (fn [block-id]
                      (when-let [ro (get @registry-state/registered-blocks block-id)]
                        [block-id ro]))
                    block-ids)]
      (when (seq ros)
        (let [registered-obj
              (.register
                ^DeferredRegister block-entities-register
                registry-name
                (reify java.util.function.Supplier
                  (get [_]
                    (let [pairs (map (fn [[block-id ^RegistryObject ro]]
                                       [block-id (.get ro)])
                                     ros)
                          block-insts (mapv second pairs)
                          block-id-by-inst (java.util.IdentityHashMap.)]
                      (doseq [[block-id inst] pairs]
                        (.put block-id-by-inst inst block-id))
                      (bootstrap/create-scripted-block-entity-type
                        tile-id
                        block-insts
                        (reify java.util.function.Function
                          (apply [_ block-inst]
                            (.get block-id-by-inst block-inst))))))))]
          (swap! registry-state/registered-block-entities assoc tile-id registered-obj))))))
