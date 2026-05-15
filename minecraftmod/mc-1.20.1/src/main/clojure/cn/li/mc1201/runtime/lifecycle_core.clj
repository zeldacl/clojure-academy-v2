(ns cn.li.mc1201.runtime.lifecycle-core
  "Loader-agnostic player lifecycle flow for runtime state.

  Platform layers provide concrete persistence/sync callbacks and event binding."
  (:require [cn.li.mcmod.hooks.core :as player-hooks])
  (:import [net.minecraft.world.entity.player Player]))

(defn- player-uuid
  [^Player player]
  (some-> player .getUUID str))

(defn on-player-login!
  [player {:keys [load-player-state! mark-player-dirty!]}]
  (when-let [uuid (player-uuid player)]
    (when load-player-state!
      (load-player-state! player))
    (player-hooks/on-player-login! uuid)
    (when mark-player-dirty!
      (mark-player-dirty! uuid))))

(defn on-player-logout!
  [player {:keys [save-player-state!]}]
  (when-let [uuid (player-uuid player)]
    (when save-player-state!
      (save-player-state! player))
    (player-hooks/on-player-logout! uuid)))

(defn on-player-clone!
  [old-player new-player alive {:keys [clone-player-state!]}]
  (when (and alive old-player new-player)
    (when clone-player-state!
      (clone-player-state! old-player new-player))
    (let [old-uuid (player-uuid old-player)
          new-uuid (player-uuid new-player)]
      (when (and old-uuid new-uuid)
        (player-hooks/on-player-clone! old-uuid new-uuid)))))

(defn on-player-death!
  [player {:keys [save-player-state!]}]
  (when-let [uuid (player-uuid player)]
    (player-hooks/on-player-death! uuid)
    (when save-player-state!
      (save-player-state! player))))

(defn on-player-tick!
  [player {:keys [mark-player-dirty! tick-sync! send-sync-fn]}]
  (when-let [uuid (player-uuid player)]
    (player-hooks/on-player-tick! uuid)
    (when mark-player-dirty!
      (mark-player-dirty! uuid))
    (when tick-sync!
      (tick-sync! send-sync-fn))))
