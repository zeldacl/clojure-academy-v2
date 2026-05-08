(ns cn.li.mc1201.installer
  (:require [cn.li.mc1201.bootstrap.installer-core :as core]))

(defn install-block-state-protocol-only!
  "Install only BlockState protocol extensions (no Level extensions)."
  [adapter]
  (core/install-block-state-protocol-only! adapter))

(defn install-item-protocols-only!
  "Install only item protocols for ItemStack/Item classes."
  [adapter]
  (core/install-item-protocols-only! adapter))

(defn install-entity-protocols-only!
  "Install only entity/player/inventory/menu protocol extensions."
  [adapter]
  (core/install-entity-protocols-only! adapter))

(defn install-resource-factory-only!
  "Install only the shared resource location factory.

  Kept separate for incremental platform migration."
  []
  (core/install-resource-factory-only!))

(defn install-item-factories-only!
  "Install only item var-root factories, without protocol extensions.

  This is bootstrap-safe and useful for incremental migration on sensitive loaders."
  [item-stack-of-fn create-item-stack-by-id-fn item-stack-empty?-fn]
  (core/install-item-factories-only!
   item-stack-of-fn
   create-item-stack-by-id-fn
   item-stack-empty?-fn))

(defn install-be-fns-only!
  "Install only block-entity related var-root function hooks.

  `fns-map` keys:
  :be-get-level, :be-get-world, :be-get-custom-state,
  :be-set-custom-state!, :be-get-block-id, :be-set-changed!"
  [fns-map]
  (core/install-be-fns-only! fns-map))

(defn install-world-fns-only!
  "Install only world-related var-root function hooks.

  `fns-map` keys:
  :world-get-tile-entity, :world-get-block-state, :world-set-block,
  :world-remove-block, :world-break-block, :world-place-block-by-id,
  :world-is-chunk-loaded?, :world-get-day-time, :world-get-dimension-id,
  :world-get-players, :world-is-raining, :world-is-client-side, :world-can-see-sky"
  [fns-map]
  (core/install-world-fns-only! fns-map))

(defn install-platform-core!
  [adapter]
  (core/install-platform-core! adapter))

(defn install-foundation!
  "Install bootstrap-safe shared foundations only (NBT + position).

  Useful for incremental platform migration where entity/world/item logic
  still stays in platform-specific code."
  ([] (core/install-foundation! nil))
  ([adapter]
   (core/install-foundation! adapter)))
