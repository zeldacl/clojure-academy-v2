(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mag-movement
  (:require [cn.li.ac.ability.client.arc-patterns :as arc-patterns]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]))

(def ^:private loop-sound (modid/namespaced-path "em.move_loop"))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :effect-state)
                 (or store {:effect-state {}})
                 {:effect-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode target source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id loop-sound :volume 0.58 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :target target :ticks 0})))
      :update
      (update-in store* [:effect-state owner-key*]
                 (fn [st]
                   (if (:active? st)
                     (merge st base-meta {:target target})
                     (merge base-meta {:active? true :target target :ticks 0}))))
      :end
      (update store* :effect-state dissoc owner-key*)
      store*)))

(defn- tick-state!
  [store]
  (let [store* (if (contains? (or store {}) :effect-state)
                 (or store {:effect-state {}})
                 {:effect-state {}})]
    (update store* :effect-state
      (fn [states]
        (reduce-kv
          (fn [acc owner-key st]
            (if-not (:active? st)
              acc
              (let [ticks (inc (long (or (:ticks st) 0)))]
                (when (zero? (mod ticks 10))
                  (client-sounds/queue-sound-effect! (:queue-owner st)
                    {:type :sound :sound-id loop-sound :volume 0.4 :pitch 1.0}))
                (assoc acc owner-key (assoc st :ticks ticks)))))
          {}
          states)))))

(def ^:private mag-movement-pattern
  (arc-patterns/get-pattern :thin-continuous))

(defn- build-plan
  "Continuously-guided beam (hand-position -> live target): the zigzag path
  is re-derived every frame from the two live endpoints — unlike a fire-and-
  forget arc it has no fixed lifetime to precompute vertices once for, so
  only the per-arc constants (pattern lookup, wiggle phase/amplitude) are
  hoisted out of the segment loop, matching build-arc-plan's per-call cost
  shape in arc_beam.clj."
  [camera-pos hand-center-pos tick]
  (let [mag-move (some (fn [st]
                         (when (and (:active? st)
                                    (or (nil? (:source-player-id st))
                                        (nil? (:player-uuid hand-center-pos))
                                        (= (str (:source-player-id st))
                                           (str (:player-uuid hand-center-pos)))))
                           st))
                       (vals (:effect-state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :mag-movement))))]
    (when (and hand-center-pos
               (:active? mag-move)
               (map? (:target mag-move)))
      (let [hand-v (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
            target-v (vec3/map->v3 (:target mag-move))
            vertices (arc-patterns/generate-zigzag-segments hand-v target-v mag-movement-pattern)]
        {:ops (vec (ru/zigzag-arc-ops (vec3/map->v3 camera-pos) vertices mag-movement-pattern
                                      {:life-ratio 1.0
                                       :wiggle-phase (arc-patterns/wiggle-phase)
                                       :effective-wiggle (arc-patterns/effective-wiggle-amount mag-movement-pattern 1.0)}))}))))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mag-movement :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mag-movement :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mag-movement :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :mag-movement
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :mag-movement [_ store owner-key]
  (update store :effect-state dissoc owner-key))
