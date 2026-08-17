(ns cn.li.ac.ability.client.fx-spec
  "Declarative client-side ability FX registration.

  Skills register level/hand runtimes through `register!` instead of
  hand-written `fx-registry` case blocks."
  (:require [cn.li.ac.client.effect-controller :as vfx]))

(def ^:private meta-keys
  [:effect-instance-id :source-player-id :world-id])

(defn default-owner-key
  ([ctx-id]
   (default-owner-key ctx-id nil))
  ([ctx-id payload]
   (cond
     (and (map? payload) (:effect-instance-id payload))
     [:effect-instance (:effect-instance-id payload)]

     ctx-id
     [:ctx ctx-id]

     (and (map? payload) (:source-player-id payload))
     [:source-player (:source-player-id payload)]

     :else
     [:ctx ctx-id])))

(defn select-meta
  [payload]
  (select-keys (or payload {}) meta-keys))

(defn- register-runtime!
  [effect-id {:keys [level hand]}]
  (vfx/register-effect!
    effect-id
    {:level (when level
              (select-keys level [:initial-state :enqueue-state-fn :tick-state-fn
                                  :build-plan-fn :empty-state? :fov-offset-fn :clear-owner-fn]))
     :hand (when hand
             (select-keys hand [:initial-state :enqueue-state-fn :tick-state-fn
                                :transform-fn :clear-owner-fn]))})
  nil)

(defn register!
  "Register one ability FX spec.

  `spec` keys:
    :id    effect keyword
    :level optional level runtime map
    :hand  optional hand runtime map

  A :channels key is tolerated (arc_beam.clj's build-spec still constructs
  one) but ignored -- the channel/topic transport it used to feed
  (vfx/register-channel!/dispatch-channel!) was removed as dead code: no
  content ever populated :channels with a real :topic (combat signals reach
  content through effect_controller.clj's dispatch-signal! directly).
  See docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md E section."
  [{:keys [id level hand]}]
  (when-not (keyword? id)
    (throw (IllegalArgumentException. "register-fx-spec!: id must be keyword")))
  (register-runtime! id {:level level :hand hand})
  nil)

;; ---------------------------------------------------------------------------
;; Owner-state helpers (default shape {:states {owner-key state}})
;; ---------------------------------------------------------------------------

(defn states-store
  [initial-state]
  {:states (or (:states initial-state) {})})

(defn state-values
  [store]
  (vals (:states (or store {}))))

(defn get-owner-state
  [store owner-key default-state]
  (get-in (or store {}) [:states owner-key] default-state))

(defn update-owner-state
  [store owner-key f & args]
  (update (or store {}) :states
          (fn [states]
            (let [current (get states owner-key)
                  next-state (apply f current args)]
              (if (some? next-state)
                (assoc states owner-key next-state)
                (dissoc states owner-key))))))

(defn clear-owner-in-store
  [store owner-key]
  (update (or store {}) :states dissoc owner-key))

(defn matching-active-owner
  "Find first owner state matching `pred`, optionally filtered by source-player-id."
  [store pred hand-center-pos]
  (some (fn [st]
          (when (and (pred st)
                     (or (nil? (:source-player-id st))
                         (nil? (:player-uuid hand-center-pos))
                         (= (str (:source-player-id st))
                            (str (:player-uuid hand-center-pos)))))
            st))
        (state-values store)))
