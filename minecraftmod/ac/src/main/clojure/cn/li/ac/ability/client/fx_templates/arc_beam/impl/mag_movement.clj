(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mag-movement
  (:require [cn.li.ac.ability.client.arc-patterns :as arc-patterns]
            [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(def ^:private loop-sound (modid/namespaced-path "em.move_loop"))
(defn- loop-sound-key [ctx-id] (str "mag-movement/" ctx-id))

(defn- start-loop-sound! [ctx-id source-player-id]
  ;; Original c_startEffect: FollowEntitySound(player, SOUND).setLoop(), stopped
  ;; in c_endEffect. Queuing em.move_loop as one-shots instead left the last
  ;; sample playing past the end of the skill with no handle to stop it, and it
  ;; is a loop sample — so it kept going long after the arc was gone.
  (try
    (client-bridge/run-client-effect!
     :mcmod/start-loop-sound-at-player
     {:key (loop-sound-key ctx-id)
      :sound-id loop-sound
      :owner-uuid (str source-player-id)
      :volume 0.58
      :pitch 1.0})
    (catch Throwable _ nil)))

(defn- stop-loop-sound! [ctx-id]
  (try
    (client-bridge/run-client-effect!
     :mcmod/stop-loop-sound
     {:key (loop-sound-key ctx-id)})
    (catch Throwable _ nil)))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :effect-state)
                 (or store {:effect-state {}})
                 {:effect-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode target source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (start-loop-sound! ctx-id source-player-id)
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :target target :ticks 0})))
      :update
      (update-in store* [:effect-state owner-key*]
                 (fn [st]
                   (if (:active? st)
                     (merge st base-meta {:target target})
                     (merge base-meta {:active? true :target target :ticks 0}))))
      :end
      (do
        (stop-loop-sound! ctx-id)
        (update store* :effect-state dissoc owner-key*))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (if (contains? (or store {}) :effect-state)
                 (or store {:effect-state {}})
                 {:effect-state {}})]
    (update store* :effect-state
      (fn [states]
        (store-tick/map-active-states
         states
         (fn [_owner-key st]
           ;; The charge loop is one continuous FollowEntitySound started on
           ;; :start and stopped on :end — not a re-queued one-shot.
           (assoc st :ticks (inc (long (or (:ticks st) 0))))))))))

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
                                      ;; life-fade-alpha fades OUT over the last
                                      ;; 20% of life, so life-ratio 1.0 ("about to
                                      ;; die") multiplies every colour by alpha 0
                                      ;; — the arc was emitted fully transparent
                                      ;; every frame. This arc is upstream's
                                      ;; thinContiniousArc: it lives as long as the
                                      ;; skill and never fades, so it sits in the
                                      ;; curve's flat full-brightness middle.
                                      {:life-ratio 0.5
                                       :wiggle-phase (arc-patterns/wiggle-phase)
                                       :effective-wiggle (arc-patterns/effective-wiggle-amount mag-movement-pattern 0.5)}))}))))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mag-movement :level] [_ _] {:effect-state {}})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mag-movement :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mag-movement :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :mag-movement
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :mag-movement [_ store owner-key]
  ;; Externally aborted contexts never get :end — stop the loop here too, or it
  ;; plays forever.
  (stop-loop-sound! (second owner-key))
  (update store :effect-state dissoc owner-key))
