(ns cn.li.mc1201.block.blockstate-properties
  "Shared mc1201 orchestration for platform BlockState property adapters.

  BlockState property creation here uses only vanilla Minecraft APIs, so the
  registry and default constructors can live entirely in mc1201 rather than be
  mirrored by Forge/Fabric wrapper namespaces."
  (:require [cn.li.mcmod.block.blockstate-properties :as shared]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.level.block.state.properties IntegerProperty BooleanProperty BlockStateProperties]))

(defn create-adapter-registry []
  (shared/create-property-registry))

(defonce ^:private property-registry
  (create-adapter-registry))

(defn- create-integer-property [property-name min-value max-value]
  (IntegerProperty/create property-name (int min-value) (int max-value)))

(defn- create-boolean-property [property-name]
  (BooleanProperty/create property-name))

(defn- create-horizontal-facing-property
  [_property-name]
  BlockStateProperties/HORIZONTAL_FACING)

(defn register-block-properties!
  [property-registry block-id block-state-properties create-integer-fn create-boolean-fn create-facing-fn]
  (shared/register-block-properties!
   property-registry block-id block-state-properties
   create-integer-fn
   create-boolean-fn
   create-facing-fn))

(defn register-default-block-properties!
  [block-id block-state-properties]
  (register-block-properties!
   property-registry
   block-id
   block-state-properties
   create-integer-property
   create-boolean-property
   create-horizontal-facing-property))

(defn get-property
  ([property-registry block-id property-key]
   (shared/get-property property-registry block-id property-key))
  ([block-id property-key]
   (shared/get-property property-registry block-id property-key)))

(defn get-all-properties
  ([property-registry block-id]
   (shared/get-all-properties property-registry block-id))
  ([block-id]
   (shared/get-all-properties property-registry block-id)))

(defn init-all-properties!
  ([]
   (init-all-properties!
    "mc1201 shared adapter"
    property-registry
    registry-metadata/get-block-state-properties
    create-integer-property
    create-boolean-property
    create-horizontal-facing-property)
   (log/info "Shared BlockState properties initialized"))
  ([platform-label property-registry resolve-block-properties-fn create-integer-fn create-boolean-fn create-facing-fn]
   (log/info (str "Initializing BlockState properties (" platform-label ")..."))
   (doseq [block-id (registry-metadata/get-all-block-ids)]
     (when-let [props (resolve-block-properties-fn block-id)]
       (register-block-properties!
        property-registry
        block-id
        props
        create-integer-fn
        create-boolean-fn
        create-facing-fn)))))
