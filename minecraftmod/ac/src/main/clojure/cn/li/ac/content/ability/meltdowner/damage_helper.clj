(ns cn.li.ac.content.ability.meltdowner.damage-helper
  "Meltdowner damage helper: mark targets and amplify incoming damage while marked."
  (:require [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.ac.ability.service.player-runtime-commands :as prt-cmd]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.mcmod.hooks.core :as hooks]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(def ^:private rad-mark-fx-channel :rad-intensify/fx-mark)

(defonce ^:private last-radiation-tick-id* (atom nil))

(defn- normalize-id
  [id]
  (when id (str id)))

(defn- current-server-tick-id
  []
  (some-> hooks/*player-state-owner* :server-tick-id))

(defn clear-all-marks!
  []
  (let [session-id (prt-cmd/session-id)
        store-ref (store/get-store)]
    (doseq [player-uuid (store/list-players store-ref session-id)]
      (prt-cmd/run-for-player!
       player-uuid
       {:command :clear-radiation-marks :clear-all? true}))
    (reset! last-radiation-tick-id* nil))
  nil)

(defn reset-marks-for-test!
  ([]
   (reset-marks-for-test! {}))
  ([snapshot]
   (clear-all-marks!)
   (doseq [[target-id mark] snapshot]
     (when-let [source-id (:source-player-id mark)]
       (prt-cmd/run-for-player!
        source-id
        {:command :mark-radiation-target
         :target-id target-id
         :mark mark})))
   nil))

(defn marks-snapshot
  []
  (prt-cmd/radiation-marks-snapshot))

(defn clear-mark!
  ([target-id]
   (when-let [target-key (normalize-id target-id)]
     (let [session-id (prt-cmd/session-id)
           store-ref (store/get-store)]
       (doseq [player-uuid (store/list-players store-ref session-id)]
         (prt-cmd/run-for-player!
          player-uuid
          {:command :clear-radiation-marks :target-id target-key}))))
   nil)
  ([_source-player-id target-id]
   (clear-mark! target-id)))

(defn clear-target-mark!
  [target-id]
  (clear-mark! target-id)
  nil)

(defn clear-target-marks!
  [target-id]
  (clear-target-mark! target-id))

(defn clear-source-marks!
  [source-player-id]
  (when source-player-id
    (prt-cmd/run-for-player!
     source-player-id
     {:command :clear-radiation-marks :source-player-id source-player-id}))
  nil)

(defn clear-expired-marks!
  []
  (let [session-id (prt-cmd/session-id)
        store-ref (store/get-store)]
    (doseq [player-uuid (store/list-players store-ref session-id)]
      (prt-cmd/run-for-player!
       player-uuid
       {:command :clear-radiation-marks :clear-expired? true})))
  nil)

(defn tick-marks!
  []
  (let [tick-id (current-server-tick-id)]
    (when (or (nil? tick-id) (not= tick-id @last-radiation-tick-id*))
      (when tick-id
        (reset! last-radiation-tick-id* tick-id))
      (let [session-id (prt-cmd/session-id)
            store-ref (store/get-store)]
        (doseq [player-uuid (store/list-players store-ref session-id)]
          (prt-cmd/run-for-player!
           player-uuid
           {:command :tick-radiation-marks})))))
  nil)

(defn- learned-rad-intensify?
  [player-id]
  (boolean
    (when-let [state (skill-effects/get-player-state player-id)]
      (adata/is-learned? (:ability-data state) :rad-intensify))))

(defn- emit-mark-fx!
  [source-id {:keys [ctx-id target-pos mark]}]
  (when (and source-id ctx-id mark)
    (let [payload (cond-> {:mode :mark
                           :target-id (:target-id mark)
                           :ticks-left (long (or (:ticks-left mark) 0))
                           :rate (double (or (:rate mark) 1.0))
                           :source-player-id source-id}
                    (map? target-pos)
                    (merge {:x (double (or (:x target-pos) 0.0))
                            :y (double (or (:y target-pos) 0.0))
                            :z (double (or (:z target-pos) 0.0))}))]
      (ctx-mgr/push-channel-to-player! source-id ctx-id rad-mark-fx-channel payload)
      (ctx-mgr/push-channel-to-nearby-players! ctx-id rad-mark-fx-channel payload))))

(defn mark-target!
  ([attacker-id target-id]
   (mark-target! attacker-id target-id nil))
  ([attacker-id target-id {:keys [ctx-id target-pos] :as _fx-context}]
   (let [source-id (normalize-id attacker-id)
         marked-target-id (normalize-id target-id)]
     (when (and source-id marked-target-id (learned-rad-intensify? source-id))
       (let [source-ticks (long (or (:ticks-left (get (marks-snapshot) marked-target-id)) 0))
             mark-ticks (long (max 60 (rad/mark-duration-ticks) source-ticks))
             mark-rate (rad/rate source-id)
             mark {:source-player-id source-id
                   :target-id marked-target-id
                   :ticks-left mark-ticks
                   :rate mark-rate
                   :updated-at-tick (current-server-tick-id)}]
         (prt-cmd/run-for-player!
          source-id
          {:command :mark-radiation-target
           :target-id marked-target-id
           :mark mark})
         (emit-mark-fx! source-id {:ctx-id ctx-id
                                   :target-pos target-pos
                                   :mark mark}))))))

(defn- active-mark
  [target-id]
  (let [target-key (normalize-id target-id)]
    (when-let [mark (prt-cmd/radiation-marks-for-target target-key)]
      (if (pos? (long (or (:ticks-left mark) 0)))
        mark
        (do
          (clear-mark! target-id)
          nil)))))

(defn- damage-handler
  [player-id attacker-id damage _damage-source]
  (if-let [{:keys [source-player-id target-id rate]} (active-mark player-id)]
    (let [mark-rate (double rate)]
      [(* (double damage) mark-rate)
       {:handler :meltdowner/rad-intensify
        :source-player-id source-player-id
        :target-id target-id
        :rate mark-rate}])
    [(double damage) nil]))

(defn ensure-damage-handler!
  []
  (damage-runtime/register-damage-handler!
    :meltdowner/rad-intensify
    damage-handler
    90))

(defn init!
  []
  (ensure-damage-handler!)
  nil)
