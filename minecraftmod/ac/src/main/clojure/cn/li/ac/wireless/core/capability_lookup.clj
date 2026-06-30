(ns cn.li.ac.wireless.core.capability-lookup
  "Single source of truth for resolving wireless capabilities from tiles."
  (:require [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.util.log :as log]))

(defn- tile-id-for [tile]
  (or (try (platform-be/get-tile-id tile) (catch Exception _ nil))
      (when-let [block-id (try (platform-be/be-get-block-id tile) (catch Exception _ nil))]
        (tdsl/get-tile-id-for-block block-id))))

(defn tile-capability
  "Resolve a capability from a tile via tile spec metadata and platform factories."
  [tile cap-key _fallback-class]
  (when tile
    (when-let [tile-id (tile-id-for tile)]
      (when-let [spec (tdsl/get-tile tile-id)]
        (when (contains? (:capability-keys spec #{}) cap-key)
          (try
            (when-let [factory (platform-cap/get-handler-factory cap-key)]
              (factory tile nil))
            (catch Exception e
              (log/error "[wireless] tile-capability: factory threw for" cap-key
                         "on" tile-id ":" (ex-message e))
              nil)))))))
