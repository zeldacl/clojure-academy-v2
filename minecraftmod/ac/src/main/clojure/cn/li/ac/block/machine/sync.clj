(ns cn.li.ac.block.machine.sync
  "Shared server-side block state broadcast for nearby clients (no client GUI deps)."
  (:require [cn.li.ac.wireless.gui.sync.helpers :as sync-helpers]
            [cn.li.mcmod.block.state-schema :as state-schema]))

(defn broadcast-state!
  "Broadcast sync payload to nearby players. `channel` is a short domain label (e.g. \"node\", \"matrix\")."
  [world pos sync-data channel]
  (sync-helpers/broadcast-state world pos sync-data channel))

(defn broadcast-if-changed!
  "Build payload from schema+state, compare to `old-payload`, broadcast when different.

  Returns the new payload when it changed (for `::last-broadcast-state`), else nil.

  Options:
  - :extra-payload (fn [payload state pos be] -> payload') merged after schema->sync-payload
  - :channel string passed to broadcast-state!"
  [world pos schema state old-payload channel & {:keys [extra-payload be]}]
  (when (and world pos schema state)
    (let [base (state-schema/schema->sync-payload schema state pos)
          payload (if extra-payload (extra-payload base state pos be) base)]
      (when (not= payload old-payload)
        (broadcast-state! world pos payload channel)
        payload))))
