(ns cn.li.ac.wireless.core.capability-lookup
  "Single source of truth for resolving wireless capabilities from tiles.
  Extracted from capability_resolver and vblock_resolver to break the
  vblock → capability_resolver → vblock_resolver → vblock cycle.

  All capabilities must be registered via tile-logic/register-tile-capability!;
  there is no instance? fallback — a nil result means the capability is NOT present."
  (:require [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log]))

(defn tile-capability
  "Resolve a capability from a tile via the unified tile-logic system.
  Returns the capability object, or nil if not registered."
  [tile cap-key _fallback-class]
  (when tile
    (when-let [tile-id (try (platform-be/get-block-id tile)
                            (catch Exception e
                              (log/error "[wireless] tile-capability: get-block-id threw for" cap-key
                                         ":" (ex-message e))
                              nil))]
      (try (tile-logic/get-capability tile-id cap-key tile nil)
           (catch Exception e
             (log/error "[wireless] tile-capability: tile-logic threw for" cap-key
                        "on" tile-id ":" (ex-message e))
             nil)))))
