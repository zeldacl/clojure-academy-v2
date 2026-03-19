(ns cn.li.forge1201.blockstate-properties
  "Forge 1.20.1 adapter: create Minecraft BlockState Property objects from ac definitions.
   Reads definitions from my-mod.block.blockstate-property-definitions (ac); creates
   IntegerProperty/BooleanProperty and stores in registry for mod and datagen."
  (:require [my-mod.block.blockstate-property-definitions :as defs]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.level.block.state.properties IntegerProperty BooleanProperty]))

(defonce property-registry (atom {}))

(defn create-property
  [property-key property-config]
  (let [prop-type (:type property-config)
        prop-name (or (:name property-config) (name property-key))]
    (case prop-type
      :integer (let [min-val (:min property-config 0)
                     max-val (:max property-config 15)]
                 (IntegerProperty/create prop-name min-val max-val))
      :boolean (BooleanProperty/create prop-name)
      (do (log/warn "Unknown property type:" prop-type)
          nil))))

(defn register-block-properties!
  [block-id block-state-properties]
  (when block-state-properties
    (doseq [[prop-key prop-config] block-state-properties]
      (let [property (create-property prop-key prop-config)]
        (when property
          (swap! property-registry assoc [block-id prop-key] property)
          (log/debug "Registered property" (name prop-key) "for block" block-id))))))

(defn get-property
  [block-id property-key]
  (get @property-registry [block-id property-key]))

(defn get-all-properties
  [block-id]
  (let [props (filter (fn [[[bid _] _]] (= bid block-id)) @property-registry)]
    (mapv second props)))

(defn init-all-properties!
  []
  (log/info "Initializing BlockState properties (Forge adapter)...")
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when-let [props (defs/get-all-property-definitions block-id)]
      (register-block-properties! block-id props))))
