(ns cn.li.ac.content.ability.meltdowner.damage-helper
  "Meltdowner damage helper: mark targets and amplify incoming damage while marked."
  (:require [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.mcmod.hooks.core :as hooks]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(def ^:private rad-mark-fx-channel :rad-intensify/fx-mark)

(defn create-damage-helper-runtime
  ([]
   (create-damage-helper-runtime {}))
  ([{:keys [marks*]
     :or {marks* (atom {})}}]
   {::runtime ::damage-helper-runtime
    :marks* marks*
    :last-server-tick* (atom nil)}))

(def ^:dynamic *damage-helper-runtime* nil)

(defn- damage-helper-runtime?
  [runtime]
  (and (map? runtime)
       (= ::damage-helper-runtime (::runtime runtime))
  (some? (:marks* runtime))
  (some? (:last-server-tick* runtime))))

(defn call-with-damage-helper-runtime
  [runtime f]
  (when-not (damage-helper-runtime? runtime)
    (throw (ex-info "Expected damage helper runtime"
                    {:value runtime})))
  (binding [*damage-helper-runtime* runtime]
    (f)))

(defmacro with-damage-helper-runtime
  [runtime & body]
  `(call-with-damage-helper-runtime ~runtime (fn [] ~@body)))

(defn install-damage-helper-runtime!
  ([]
   (install-damage-helper-runtime! (create-damage-helper-runtime)))
  ([runtime]
   (when-not (damage-helper-runtime? runtime)
     (throw (ex-info "Expected damage helper runtime"
                     {:value runtime})))
   (alter-var-root #'*damage-helper-runtime* (constantly runtime))
   nil))

(defn clear-damage-helper-runtime!
  []
  (alter-var-root #'*damage-helper-runtime* (constantly nil))
  nil)

(defn- current-damage-helper-runtime
  []
  *damage-helper-runtime*)

(defn- require-damage-helper-runtime
  []
  (or (current-damage-helper-runtime)
      (throw (ex-info "Damage helper runtime is not bound"
                      {:required 'damage-helper-runtime}))))

(defn- marks-atom
  []
  (:marks* (require-damage-helper-runtime)))

(defn marks-snapshot
  []
  @(marks-atom))

(defn clear-all-marks!
  []
  (reset! (marks-atom) {})
  (when-let [last-server-tick* (:last-server-tick* (require-damage-helper-runtime))]
    (reset! last-server-tick* nil))
  nil)

(defn reset-marks-for-test!
  ([]
   (reset-marks-for-test! {}))
  ([snapshot]
   (clear-all-marks!)
   (when (seq snapshot)
     (reset! (marks-atom) (or snapshot {})))
   nil))

(defn- normalize-id
  [id]
  (when id (str id)))

(defn- current-server-tick-id
  []
  (some-> hooks/*player-state-owner* :server-tick-id))

(defn- clear-marks-where!
  [pred]
  (swap! (marks-atom)
         (fn [current]
           (into {}
                 (remove (fn [[k v]] (pred k v)))
                 current)))
  nil)

(defn clear-mark!
  ([target-id]
   (when-let [target-key (normalize-id target-id)]
     (swap! (marks-atom) dissoc target-key))
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
  (let [source-key (normalize-id source-player-id)]
    (clear-marks-where!
      (fn [_target-id mark]
        (= source-key (:source-player-id mark))))))

(defn clear-expired-marks!
  []
  (clear-marks-where!
    (fn [_target-id {:keys [ticks-left]}]
      (not (pos? (long (or ticks-left 0)))))))

(defn tick-marks!
  []
  (let [runtime (require-damage-helper-runtime)
        tick-id (current-server-tick-id)
        last-server-tick* (:last-server-tick* runtime)]
    (when (or (nil? tick-id) (not= tick-id @last-server-tick*))
      (when tick-id
        (reset! last-server-tick* tick-id))
      (swap! (marks-atom)
             (fn [current]
               (reduce-kv
                 (fn [acc target-id mark]
                   (let [ticks-left (dec (long (or (:ticks-left mark) 0)))]
                     (if (pos? ticks-left)
                       (assoc acc target-id (assoc mark :ticks-left ticks-left))
                       acc)))
                 {}
                 current)))))
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
       (let [source-ticks (long (or (get-in @(marks-atom) [source-id :ticks-left]) 0))
             mark-ticks (long (max 60 (rad/mark-duration-ticks) source-ticks))
             mark-rate (rad/rate source-id)
             mark {:source-player-id source-id
                   :target-id marked-target-id
                   :ticks-left mark-ticks
                   :rate mark-rate
                   :updated-at-tick (current-server-tick-id)}]
         (swap! (marks-atom) assoc marked-target-id mark)
         (emit-mark-fx! source-id {:ctx-id ctx-id
                                   :target-pos target-pos
                                   :mark mark}))))))

(defn- active-mark
  [target-id]
  (let [target-key (normalize-id target-id)]
    (when-let [{:keys [ticks-left] :as mark} (get @(marks-atom) target-key)]
      (if (pos? (long (or ticks-left 0)))
        mark
        (do
          (swap! (marks-atom) dissoc target-key)
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
  "Idempotent: (re-)install Meltdowner mark handler after test fixtures clear handlers."
  []
  (damage-runtime/register-damage-handler!
    :meltdowner/rad-intensify
    damage-handler
    90))

(defn init!
  "Explicit runtime installer for Meltdowner mark damage handler."
  []
  (ensure-damage-handler!)
  nil)
