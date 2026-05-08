(ns cn.li.mc1201.block.blockstate-properties
  "Shared mc1201 orchestration for platform BlockState property adapters.

  Platform modules provide concrete Minecraft Property constructors,
  while this namespace owns common registration flow."
  (:require [cn.li.mcmod.block.blockstate-properties :as shared]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]))

(defn create-adapter-registry []
  (shared/create-property-registry))

(defn register-block-properties!
  [property-registry block-id block-state-properties create-integer-fn create-boolean-fn create-facing-fn]
  (shared/register-block-properties!
   property-registry block-id block-state-properties
   create-integer-fn
   create-boolean-fn
   create-facing-fn))

(defn get-property [property-registry block-id property-key]
  (shared/get-property property-registry block-id property-key))

(defn get-all-properties [property-registry block-id]
  (shared/get-all-properties property-registry block-id))

(defn init-all-properties!
  [platform-label property-registry resolve-block-properties-fn create-integer-fn create-boolean-fn create-facing-fn]
  (log/info (str "Initializing BlockState properties (" platform-label ")..."))
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when-let [props (resolve-block-properties-fn block-id)]
      (register-block-properties!
       property-registry
       block-id
       props
       create-integer-fn
       create-boolean-fn
       create-facing-fn))))
