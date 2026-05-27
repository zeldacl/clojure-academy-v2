(ns cn.li.mc1201.runtime.sync-core
  "Loader-agnostic dirty-player tracking and sync flush scheduling.

  No Minecraft or loader imports — pure Clojure state management.
  Platform adapters supply explicit server-session owner data and the send-fn
  transport when calling tick-sync!."
  (:require [cn.li.mcmod.hooks.core :as power-runtime]))

(defonce ^:private scheduler-states (atom {}))
(def ^:private flush-interval-ticks 10)

(defn- session-id
  [owner]
  (let [sid (or (:server-session-id owner)
                (:session-id owner))]
    (when-not sid
      (throw (ex-info "Sync scheduler owner requires :server-session-id"
                      {:owner owner})))
    sid))

(defn- empty-scheduler-state
  []
  {:tick-counter 0
   :last-server-tick-id nil
   :dirty-players {}})

(defn- scheduler-state
  [states session-key]
  (get states session-key (empty-scheduler-state)))

(defn- current-session-tick
  [session-key]
  (:tick-counter (scheduler-state @scheduler-states session-key)))

(defn- mark-player-dirty-in-session!
  [session-key uuid]
  (let [tick (current-session-tick session-key)]
    (swap! scheduler-states update-in [session-key :dirty-players uuid]
           (fn [entry]
             (assoc (or entry {}) :last-dirty-tick tick)))))

(defn mark-player-dirty!
  [owner uuid]
  (mark-player-dirty-in-session! (session-id owner) uuid))

(defn clear-player-dirty!
  [owner uuid]
  (swap! scheduler-states update-in [(session-id owner) :dirty-players] dissoc uuid))

(defn mark-all-dirty!
  [owner]
  (let [session-key (session-id owner)
        tick (current-session-tick session-key)
        players (binding [power-runtime/*player-state-owner* owner]
                  (set (power-runtime/list-player-uuids)))]
    (swap! scheduler-states assoc-in [session-key :dirty-players]
           (into {}
                 (map (fn [uuid] [uuid {:last-dirty-tick tick}]))
                 players))))

(defn- build-sync-payload [uuid]
  (power-runtime/build-sync-payload uuid))

(defn- advance-scheduler!
  [owner]
  (let [session-key (session-id owner)
        server-tick-id (:server-tick-id owner)
        result (atom nil)]
    (swap! scheduler-states
           (fn [states]
             (let [state (scheduler-state states session-key)
                   duplicate-server-tick? (and (some? server-tick-id)
                                               (= server-tick-id (:last-server-tick-id state)))
                   next-state (if duplicate-server-tick?
                                state
                                (-> state
                                    (update :tick-counter inc)
                                    (assoc :last-server-tick-id server-tick-id)))
                   tick (:tick-counter next-state)
                   due? (and (not duplicate-server-tick?)
                             (zero? (mod tick flush-interval-ticks)))
                   dirty-uuids (when due? (keys (:dirty-players next-state)))]
               (reset! result {:advanced? (not duplicate-server-tick?)
                               :due? due?
                               :tick tick
                               :session-key session-key
                               :dirty-uuids (vec dirty-uuids)})
               (assoc states session-key next-state))))
    @result))

(defn- mark-player-flushed!
  [session-key uuid tick]
  (swap! scheduler-states update-in [session-key :dirty-players]
         (fn [dirty]
           (if-let [entry (get dirty uuid)]
             (if (<= (:last-dirty-tick entry 0) tick)
               (dissoc dirty uuid)
               (assoc dirty uuid (assoc entry :last-flush-tick tick)))
             dirty))))

(defn scheduler-snapshot
  []
  @scheduler-states)

(defn clear-session-scheduler-state!
  "Remove all scheduler state for one server session."
  [session-key]
  (swap! scheduler-states dissoc session-key)
  nil)

(defn reset-scheduler-for-test!
  ([]
   (reset-scheduler-for-test! {}))
  ([states]
   (reset! scheduler-states states)
   nil))

(defn tick-sync!
  "Flush dirty player snapshots periodically.
  send-fn: (fn [uuid payload]) — supplied by the platform network bridge."
  [send-fn owner]
  (let [{:keys [due? tick session-key dirty-uuids]} (advance-scheduler! owner)]
    (when due?
      (doseq [uuid dirty-uuids]
        (binding [power-runtime/*player-state-owner* owner]
          (when-let [payload (build-sync-payload uuid)]
            (try
              (when send-fn
                (send-fn uuid payload))
              (power-runtime/mark-player-clean! uuid)
              (mark-player-flushed! session-key uuid tick)
              (catch Throwable _
                ;; Keep the dirty marker so a later tick can retry.
                nil))))))))
