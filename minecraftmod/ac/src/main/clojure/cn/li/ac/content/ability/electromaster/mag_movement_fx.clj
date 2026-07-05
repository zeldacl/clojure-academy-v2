(ns cn.li.ac.content.ability.electromaster.mag-movement-fx
  "Client FX for Magnetic Movement: beam between hand and target."
  (:require [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.arc-patterns :as arc]))

(def ^:private loop-sound "my_mod:em.move_loop")
(def ^:private mag-movement-effect-id :mag-movement)

(defn default-mag-movement-fx-runtime-state
  []
  {:effect-state {}})

(defn mag-movement-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot mag-movement-effect-id)
      (default-mag-movement-fx-runtime-state)))

(defn reset-mag-movement-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    mag-movement-effect-id
    (default-mag-movement-fx-runtime-state))
  nil)

(defn clear-mag-movement-owner!
  [owner-key]
  (level-effects/update-effect-state!
    mag-movement-effect-id
    (fn [store]
      (let [store* (if (contains? (or store {}) :effect-state)
                     (or store (default-mag-movement-fx-runtime-state))
                     (default-mag-movement-fx-runtime-state))]
        (update store* :effect-state dissoc owner-key))))
  nil)

(defn- magnetic-beam-style [tick]
  (let [phase (* 0.9 (double tick))
        tex-phase (* 1.7 (double tick))
        wiggle (+ 0.02
                  (* 0.02 (Math/sin phase))
                  (* 0.012 (Math/sin tex-phase)))
        flicker (+ (* 0.5 (+ 1.0 (Math/sin (* 0.27 (double tick)))))
                   (* 0.5 (+ 1.0 (Math/sin (* 0.53 (double tick))))))
        show-prob (+ 0.1 (* 0.35 flicker))
        hide-prob (+ 0.6 (* 0.25 (- 1.0 flicker)))]
    {:width wiggle
     :core-width (* wiggle 0.52)
     :outer-rgb {:r 89 :g 196 :b 255}
     :outer-alpha (int (+ 45 (* 95 show-prob)))
     :inner-rgb {:r 234 :g 250 :b 255}
     :inner-alpha (int (+ 70 (* 120 hide-prob)))
     :line-rgb {:r 161 :g 236 :b 255}
     :line-alpha (int (+ 90 (* 110 flicker)))}))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :effect-state)
                 (or store (default-mag-movement-fx-runtime-state))
                 (default-mag-movement-fx-runtime-state))
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
                 (or store (default-mag-movement-fx-runtime-state))
                 (default-mag-movement-fx-runtime-state))]
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

(defn- build-plan [camera-pos hand-center-pos tick]
  (let [mag-move (some (fn [st]
                         (when (and (:active? st)
                                    (or (nil? (:source-player-id st))
                                        (nil? (:player-uuid hand-center-pos))
                                        (= (str (:source-player-id st))
                                           (str (:player-uuid hand-center-pos)))))
                           st))
                       (vals (:effect-state (mag-movement-fx-snapshot))))]
    (when (and hand-center-pos
               (:active? mag-move)
               (map? (:target mag-move)))
      {:ops (vec (ru/zigzag-arc-ops camera-pos
                                    (dissoc hand-center-pos :player-uuid)
                                    (:target mag-move)
                                    {:arc-pattern :thin-continuous
                                     :life-ratio 1.0}))})))

(defn init! []
  (fx-spec/register!
    {:id mag-movement-effect-id
     :level {:initial-state (default-mag-movement-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :mag-movement/fx-start :mode :start}
                :update {:topic :mag-movement/fx-update :mode :update}
                :end {:topic :mag-movement/fx-end :mode :end}}})
  nil)
