(ns cn.li.forge1201.blockstate-properties
  "Forge 1.20.1 adapter: create Minecraft BlockState Property objects from ac definitions."
  (:require [cn.li.mcmod.block.blockstate-properties :as shared]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]))

(defonce property-registry (shared/create-property-registry))

(defn- create-integer-property [property-name min-value max-value]
  (clojure.lang.Reflector/invokeStaticMethod
    "net.minecraft.world.level.block.state.properties.IntegerProperty"
    "create"
    (to-array [property-name (int min-value) (int max-value)])))

(defn- create-boolean-property [property-name]
  (clojure.lang.Reflector/invokeStaticMethod
    "net.minecraft.world.level.block.state.properties.BooleanProperty"
    "create"
    (to-array [property-name])))

(defn register-block-properties!
  [block-id block-state-properties]
  (shared/register-block-properties!
    property-registry block-id block-state-properties
    create-integer-property
    create-boolean-property))

(defn get-property [block-id property-key]
  (shared/get-property property-registry block-id property-key))

(defn get-all-properties [block-id]
  (shared/get-all-properties property-registry block-id))

(defn init-all-properties! []
  (log/info "Initializing BlockState properties (Forge adapter)...")
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when-let [props (registry-metadata/get-block-state-properties block-id)]
      (register-block-properties! block-id props))))
