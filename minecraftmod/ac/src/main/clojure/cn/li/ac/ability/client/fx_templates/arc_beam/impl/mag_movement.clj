(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mag-movement
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.util.math.vec3 :as vec3]
            [clojure.string :as str]))

(def ^:private loop-sound "my_mod:em.move_loop")










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

(defn- build-plan [camera-pos hand-center-pos tick]
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
      {:ops (vec (ru/zigzag-arc-ops camera-pos
                                    (dissoc hand-center-pos :player-uuid)
                                    (:target mag-move)
                                    {:arc-pattern :thin-continuous
                                     :life-ratio 1.0}))})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mag-movement :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mag-movement :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mag-movement :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :mag-movement [store owner-key]
  (update store :effect-state dissoc owner-key))
