(ns cn.li.mc1201.runtime.lifecycle-core
  "Loader-agnostic player lifecycle flow for runtime state.

  Platform layers provide concrete persistence/sync callbacks and event binding."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime]))

(defn- player-uuid
  [player]
  (some-> player .getUUID str))

(defn on-player-login!
  [player {:keys [load-player-state! mark-player-dirty!]}]
  (when-let [uuid (player-uuid player)]
    (when load-player-state!
      (load-player-state! player))
    (power-runtime/on-player-login! uuid)
    (when mark-player-dirty!
      (mark-player-dirty! uuid))))

(defn on-player-logout!
  [player {:keys [save-player-state!]}]
  (when-let [uuid (player-uuid player)]
    (when save-player-state!
      (save-player-state! player))
    (power-runtime/on-player-logout! uuid)))

(defn on-player-clone!
  [old-player new-player alive {:keys [clone-player-state!]}]
  (when (and alive old-player new-player)
    (when clone-player-state!
      (clone-player-state! old-player new-player))
    (let [old-uuid (player-uuid old-player)
          new-uuid (player-uuid new-player)]
      (when (and old-uuid new-uuid)
        (power-runtime/on-player-clone! old-uuid new-uuid)))))

(defn on-player-death!
  [player {:keys [save-player-state!]}]
  (when-let [uuid (player-uuid player)]
    (power-runtime/on-player-death! uuid)
    (when save-player-state!
      (save-player-state! player))))

(defn on-player-tick!
  [player {:keys [mark-player-dirty! tick-sync! send-sync-fn]}]
  (when-let [uuid (player-uuid player)]
    (power-runtime/on-player-tick! uuid)
    (when mark-player-dirty!
      (mark-player-dirty! uuid))
    (when tick-sync!
      (tick-sync! send-sync-fn))))
