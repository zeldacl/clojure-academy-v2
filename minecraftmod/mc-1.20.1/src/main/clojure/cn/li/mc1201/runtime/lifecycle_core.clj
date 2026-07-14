(ns cn.li.mc1201.runtime.lifecycle-core
  "Loader-agnostic player lifecycle flow for runtime state.

  Platform layers provide concrete persistence/sync callbacks and event binding."
  (:require [cn.li.mc1201.runtime.spi.server-context :as server-context-spi]
            [cn.li.mcmod.hooks.core :as player-hooks]
            [cn.li.mcmod.runtime.install :as install])
  (:import [net.minecraft.world.entity.player Player]))

(defn- player-uuid
  [^Player player]
  (some-> player .getUUID str))

(defn- send-sync-now-for-player!
  [send-sync-now! uuid]
  (let [payload (player-hooks/build-sync-payload uuid)]
    (send-sync-now! uuid payload)))

(defn- scheduler-owner
  [opts]
  (let [session-id (or (:server-session-id opts)
                       (:session-id opts))]
    (when-not session-id
      (throw (ex-info "Lifecycle sync owner requires :server-session-id"
                      {:opts (select-keys opts [:server-session-id :session-id :server-tick-id])})))
    (cond-> {:server-session-id session-id}
      (:server-tick-id opts) (assoc :server-tick-id (:server-tick-id opts)))))

(defn- server-owner
  [server]
  {:server-session-id [:server (System/identityHashCode server)]})

(defn- with-player-state-owner
  [owner f]
  (player-hooks/with-client-ctx-fn {:player-owner owner} f))

(defn on-player-login!
  [player {:keys [load-player-state! mark-player-dirty! send-sync-now! clear-player-dirty!] :as opts}]
  (when-let [uuid (player-uuid player)]
    (let [owner (scheduler-owner opts)]
      (with-player-state-owner owner
        #(do
           (when load-player-state!
             (load-player-state! player))
           (player-hooks/on-player-login! uuid)
           (when mark-player-dirty!
             (mark-player-dirty! owner uuid))
           (when send-sync-now!
             (send-sync-now-for-player! send-sync-now! uuid)
             (when clear-player-dirty!
               (clear-player-dirty! owner uuid))))))))

(defn on-player-logout!
  [player {:keys [save-player-state!] :as opts}]
  (when-let [uuid (player-uuid player)]
    (with-player-state-owner (scheduler-owner opts)
      #(do
         (when save-player-state!
           (save-player-state! player))
         (player-hooks/on-player-logout! uuid)))))

(defn on-server-stop!
  [server {:keys [cleanup-session!]}]
  (when server
    (let [owner (server-owner server)
          session-id (:server-session-id owner)]
      (with-player-state-owner owner
        #(do
           (player-hooks/on-server-stop! session-id)
           (when cleanup-session!
             (cleanup-session! session-id)))))))

(defn install-server-stop-cleanup!
  "Register the on-server-stop cleanup callback exactly once per process.
   Each call builds a fresh closure, so the SPI's own by-id callback dedup
   can't catch repeats here — this guard is load-bearing. Process-scoped
   (not Framework-scoped): the SPI's callback list is its own process-level
   state with its own test-reset (reset-server-context-spi-for-test!)."
  [{:keys [cleanup-session!] :as opts}]
  (server-context-spi/install-server-context!)
  (install/process-once! ::server-stop-cleanup-installed
    #(server-context-spi/on-server-unavailable!
       (fn [server]
         (on-server-stop! server (assoc opts :cleanup-session! cleanup-session!)))))
  nil)

(defn on-player-clone!
  [old-player new-player _alive {:keys [clone-player-state! mark-player-dirty! send-sync-now! clear-player-dirty!] :as opts}]
  (when (and old-player new-player)
    (let [owner (scheduler-owner opts)]
      (with-player-state-owner owner
        #(do
           (when clone-player-state!
             (clone-player-state! old-player new-player))
           (let [old-uuid (player-uuid old-player)
                 new-uuid (player-uuid new-player)]
             (when (and old-uuid new-uuid)
               (player-hooks/on-player-clone! old-uuid new-uuid)
          (when mark-player-dirty!
            (mark-player-dirty! owner new-uuid))
          (when send-sync-now!
            (send-sync-now-for-player! send-sync-now! new-uuid)
            (when clear-player-dirty!
                       (clear-player-dirty! owner new-uuid))))))))))

(defn on-player-death!
  [player {:keys [save-player-state!] :as opts}]
  (when-let [uuid (player-uuid player)]
    (with-player-state-owner (scheduler-owner opts)
      #(do
         (player-hooks/on-player-death! uuid)
         (when save-player-state!
           (save-player-state! player))))))

(defn on-player-dimension-change!
  [player from-dim to-dim {:keys [mark-player-dirty! tick-sync! send-sync-fn send-sync-now! clear-player-dirty!] :as opts}]
  (when-let [uuid (player-uuid player)]
    (let [owner (scheduler-owner opts)]
      (with-player-state-owner owner
        #(do
           (player-hooks/on-player-dimension-change! uuid from-dim to-dim)
           (when mark-player-dirty!
             (mark-player-dirty! owner uuid))
           (if send-sync-now!
             (do
               (send-sync-now-for-player! send-sync-now! uuid)
               (when clear-player-dirty!
                 (clear-player-dirty! owner uuid)))
             (when tick-sync!
               (tick-sync! send-sync-fn owner))))))))

(defn on-player-tick!
  "Only marks the player dirty in the sync scheduler when the ability runtime
  actually changed something this tick (player-hooks/player-state-dirty?) —
  idle players stop paying for the periodic full-snapshot flush. tick-sync!
  still runs every tick regardless, so its own low-frequency full-sync
  fallback (see sync-core) keeps working."
  [player {:keys [mark-player-dirty! tick-sync! send-sync-fn] :as opts}]
  (when-let [uuid (player-uuid player)]
    (let [owner (scheduler-owner opts)]
      (with-player-state-owner owner
        #(do
           (player-hooks/on-player-tick! uuid)
           (when (and mark-player-dirty!
                      (player-hooks/player-state-dirty? uuid))
             (mark-player-dirty! owner uuid))
           (when tick-sync!
             (tick-sync! send-sync-fn owner)))))))
