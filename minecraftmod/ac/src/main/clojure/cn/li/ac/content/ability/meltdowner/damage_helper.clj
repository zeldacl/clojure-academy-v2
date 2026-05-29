(ns cn.li.ac.content.ability.meltdowner.damage-helper
  "Meltdowner damage helper: mark targets and amplify incoming damage while marked."
  (:require [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(defn create-damage-helper-runtime
  ([]
   (create-damage-helper-runtime {}))
  ([{:keys [marks*]
     :or {marks* (atom {})}}]
   {::runtime ::damage-helper-runtime
    :marks* marks*}))

(def ^:dynamic *damage-helper-runtime* nil)

(defn- damage-helper-runtime?
  [runtime]
  (and (map? runtime)
       (= ::damage-helper-runtime (::runtime runtime))
       (some? (:marks* runtime))))

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

(defn reset-marks-for-test!
  ([]
   (reset-marks-for-test! {}))
  ([snapshot]
  (reset! (marks-atom) (or snapshot {}))
   nil))

(defn- normalize-id
  [id]
  (when id (str id)))

(defn- mark-key
  [source-player-id target-id]
  [(normalize-id source-player-id) (normalize-id target-id)])

(defn- now-ms []
  (System/currentTimeMillis))

(defn- clear-marks-where!
  [pred]
  (swap! (marks-atom)
         (fn [current]
           (into {}
                 (remove (fn [[k v]] (pred k v)))
                 current)))
  nil)

(defn clear-mark!
  [source-player-id target-id]
  (swap! (marks-atom) dissoc (mark-key source-player-id target-id))
  nil)

(defn clear-target-marks!
  [target-id]
  (let [target-key (normalize-id target-id)]
    (clear-marks-where!
      (fn [[_source-id marked-target-id] _mark]
        (= marked-target-id target-key)))))

(defn clear-source-marks!
  [source-player-id]
  (let [source-key (normalize-id source-player-id)]
    (clear-marks-where!
      (fn [[marked-source-id _target-id] _mark]
        (= marked-source-id source-key)))))

(defn clear-expired-marks!
  []
  (let [t (now-ms)]
    (clear-marks-where!
      (fn [_key {:keys [expire-at]}]
        (not (and expire-at (> (long expire-at) t)))))))

(defn- learned-rad-intensify?
  [player-id]
  (boolean
    (when-let [state (skill-effects/get-player-state player-id)]
      (adata/is-learned? (:ability-data state) :rad-intensify))))

(defn mark-target!
  [attacker-id target-id]
  (let [source-id (normalize-id attacker-id)
        marked-target-id (normalize-id target-id)]
    (when (and source-id marked-target-id (learned-rad-intensify? source-id))
      (let [expire (+ (now-ms) (rad/mark-duration-ms))
            mark-rate (rad/rate source-id)]
        (swap! (marks-atom) assoc (mark-key source-id marked-target-id)
               {:source-player-id source-id
                :target-id marked-target-id
                :expire-at expire
                :rate mark-rate})))))

(defn- active-mark
  [attacker-id target-id]
  (let [key (mark-key attacker-id target-id)
        t (now-ms)]
    (when-let [{:keys [expire-at] :as mark} (get @(marks-atom) key)]
      (if (> (long expire-at) t)
        mark
        (do
          (swap! (marks-atom) dissoc key)
          nil)))))

(defn- damage-handler
  [player-id attacker-id damage _damage-source]
  (if-let [{:keys [source-player-id target-id rate]} (active-mark attacker-id player-id)]
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
