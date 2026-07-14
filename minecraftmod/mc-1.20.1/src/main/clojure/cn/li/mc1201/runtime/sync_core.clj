(ns cn.li.mc1201.runtime.sync-core
  "Loader-agnostic dirty-player tracking and sync flush scheduling.

  No Minecraft or loader imports — pure Clojure state management.
  Platform adapters supply explicit server-session owner data and the send-fn
  transport when calling tick-sync!."
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.runtime.deferred :as deferred]))

(def ^:private default-flush-interval-ticks 10)

;; Low-frequency full-sync safety net: with per-player dirty tracking (see
;; lifecycle-core/on-player-tick!), a write path that changes runtime state
;; without going through the reducer's dirty marking would silently stop
;; syncing that player. Force a full snapshot of every online player at this
;; interval regardless of tracked dirty state so such a gap self-heals.
(def ^:private full-sync-interval-ticks 200)

(defn create-sync-scheduler-runtime
  ([]
   (create-sync-scheduler-runtime {}))
  ([{:keys [flush-interval-ticks]
     :or {flush-interval-ticks default-flush-interval-ticks}}]
   {::runtime ::sync-scheduler-runtime
    :scheduler-states* (atom {})
    :flush-interval-ticks flush-interval-ticks}))

(def ^:private default-sync-scheduler-runtime-holder
  (deferred/deferred #(create-sync-scheduler-runtime)))

(def ^:private sync-scheduler-runtime-override
  "Plain root var, nil in production. Test-only swap target for
   call-with-sync-scheduler-runtime — replaces the prior ^:dynamic +
   binding pair. Single-threaded test execution only (clojure.test runs
   deftest forms sequentially); not safe for concurrent binders."
  nil)

(defn- sync-scheduler-runtime?
  [runtime]
  (and (map? runtime)
       (= ::sync-scheduler-runtime (::runtime runtime))
       (some? (:scheduler-states* runtime))))

(defn call-with-sync-scheduler-runtime
  [runtime f]
  (when-not (sync-scheduler-runtime? runtime)
    (throw (ex-info "Expected sync scheduler runtime"
                    {:runtime runtime})))
  (let [prev sync-scheduler-runtime-override]
    (alter-var-root #'sync-scheduler-runtime-override (constantly runtime))
    (try
      (f)
      (finally
        (alter-var-root #'sync-scheduler-runtime-override (constantly prev))))))

(defmacro with-sync-scheduler-runtime
  [runtime & body]
  `(call-with-sync-scheduler-runtime ~runtime (fn [] ~@body)))

(defn- current-sync-scheduler-runtime
  []
  (or sync-scheduler-runtime-override
      @default-sync-scheduler-runtime-holder))

(defn- scheduler-states-atom
  []
  (:scheduler-states* (current-sync-scheduler-runtime)))

(defn- scheduler-states-snapshot
  []
  @(scheduler-states-atom))

(defn- update-scheduler-states!
  [f & args]
  (apply swap! (scheduler-states-atom) f args))

(defn- flush-interval-ticks
  []
  (:flush-interval-ticks (current-sync-scheduler-runtime)))

(defn- session-id
  [owner]
  (let [sid (:server-session-id owner)]
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
  (merge (empty-scheduler-state)
         (get states session-key)))

(defn- current-session-tick
  [session-key]
	(:tick-counter (scheduler-state (scheduler-states-snapshot) session-key)))

(defn- mark-player-dirty-in-session!
  [session-key uuid]
  (let [tick (current-session-tick session-key)]
		(update-scheduler-states! update-in [session-key :dirty-players uuid]
           (fn [entry]
             (assoc (or entry {}) :last-dirty-tick tick)))))

(defn mark-player-dirty!
  [owner uuid]
  (mark-player-dirty-in-session! (session-id owner) uuid))

(defn clear-player-dirty!
  [owner uuid]
  (update-scheduler-states! update-in [(session-id owner) :dirty-players] dissoc uuid))

(defn mark-all-dirty!
  [owner]
  (let [session-key (session-id owner)
        tick (current-session-tick session-key)
        players (power-runtime/with-client-ctx {:player-owner owner}
                  (set (power-runtime/list-player-uuids)))]
		(update-scheduler-states! assoc-in [session-key :dirty-players]
           (into {}
                 (map (fn [uuid] [uuid {:last-dirty-tick tick}])
                 players)))))

(defn- build-sync-payload [uuid full?]
  (power-runtime/build-sync-payload uuid full?))

(defn- advance-scheduler!
  [owner]
  (let [session-key (session-id owner)
        server-tick-id (:server-tick-id owner)
        result (atom nil)]
		(update-scheduler-states!
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
							 (zero? (mod tick (flush-interval-ticks))))
                   force-full? (and due? (zero? (mod tick full-sync-interval-ticks)))
                   dirty-uuids (when due? (keys (:dirty-players next-state)))]
               (reset! result {:advanced? (not duplicate-server-tick?)
                               :due? due?
                               :force-full? force-full?
                               :tick tick
                               :session-key session-key
                               :dirty-uuids (vec dirty-uuids)})
               (assoc states session-key next-state))))
    @result))

(defn- mark-player-flushed!
  [session-key uuid tick]
	(update-scheduler-states! update-in [session-key :dirty-players]
         (fn [dirty]
           (if-let [entry (get dirty uuid)]
             (if (<= (:last-dirty-tick entry 0) tick)
               (dissoc dirty uuid)
               (assoc dirty uuid (assoc entry :last-flush-tick tick)))
             dirty))))

(defn scheduler-snapshot
  []
	(scheduler-states-snapshot))

(defn clear-session-scheduler-state!
  "Remove all scheduler state for one server session."
  [session-key]
	(update-scheduler-states! dissoc session-key)
  nil)

(defn reset-scheduler-for-test!
  ([]
   (reset-scheduler-for-test! {}))
  ([states]
		(reset! (scheduler-states-atom) states)
   nil))

(defn tick-sync!
  "Flush dirty player snapshots periodically. Every full-sync-interval-ticks,
  flushes all online players regardless of tracked dirty state (safety net —
  see full-sync-interval-ticks).
  send-fn: (fn [uuid payload]) — supplied by the platform network bridge."
  [send-fn owner]
  (let [{:keys [due? force-full? tick session-key dirty-uuids]} (advance-scheduler! owner)]
    (when due?
      ;; `owner` is invariant across this whole flush — establish the client
      ;; ctx ThreadLocal once instead of once per uuid (was N pushes/pops per
      ;; flush, now 1).
      (power-runtime/with-client-ctx {:player-owner owner}
        (let [flush-uuids (if force-full?
                            (vec (into (set dirty-uuids)
                                       (power-runtime/list-player-uuids)))
                            dirty-uuids)]
          (doseq [uuid flush-uuids]
            (when-let [payload (build-sync-payload uuid force-full?)]
              (try
                (when send-fn
                  (send-fn uuid payload))
                (power-runtime/mark-player-clean! uuid)
                (mark-player-flushed! session-key uuid tick)
                (catch Throwable _
                  ;; Keep the dirty marker so a later tick can retry.
                  nil)))))))))
