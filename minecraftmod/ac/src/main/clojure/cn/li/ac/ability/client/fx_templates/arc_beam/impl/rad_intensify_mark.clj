(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.rad-intensify-mark
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
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str]))

(defn- mark-key
  [{:keys [owner-key target-id]}]
  [owner-key (str target-id)])

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store {:marks {}})
        owner-key* (or owner-key [:ctx ctx-id])
        target-id (or (:target-id payload) "")
        ticks-left (long (max 1 (or (:ticks-left payload) 60)))
        rate (double (or (:rate payload) 1.0))
        mark {:owner-key owner-key*
              :ctx-id ctx-id
              :target-id (str target-id)
              :ticks-left ticks-left
              :rate rate
              :x (some-> (:x payload) double)
              :y (some-> (:y payload) double)
              :z (some-> (:z payload) double)}]
    (assoc-in store* [:marks (mark-key mark)] mark)))

(defn- tick-state!
  [store]
  (let [store* (or store {:marks {}})]
    (update store* :marks
            (fn [marks]
              (into {}
                    (keep (fn [[k mark]]
                            (let [ttl (dec (long (or (:ticks-left mark) 0)))]
                              (when (pos? ttl)
                                [k (assoc mark :ticks-left ttl)]))))
                    marks)))))

(defn- mark-ops
  [mark]
  (let [ttl (long (or (:ticks-left mark) 0))
        alpha (int (max 40 (min 180 (* 3 ttl))))
        sparks (rand-int 4)]
    (when (and (number? (:x mark))
               (number? (:y mark))
               (number? (:z mark))
               (pos? ttl)
               (pos? sparks))
      (let [cx (double (:x mark))
            cy (+ 0.8 (double (:y mark)))
            cz (double (:z mark))
            base-angle (* 0.22 ttl)]
        (for [i (range sparks)]
          (let [angle (+ base-angle (* i 2.1))
                dx (* 0.35 (Math/cos angle))
                dz (* 0.35 (Math/sin angle))]
            (ru/line-op (vec3/v3 cx cy cz)
                        (vec3/v3 (+ cx dx) (+ cy 0.2) (+ cz dz))
                        {:r 255 :g 120 :b 40 :a alpha})))))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick & _more]
  (let [ops (->> (:marks (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :rad-intensify-mark))
                 vals
                 (mapcat mark-ops)
                 vec)]
    (when (seq ops)
      {:ops ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:rad-intensify-mark :level] [_ _] {:marks {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:rad-intensify-mark :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:rad-intensify-mark :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :rad-intensify-mark
  [_effect-id camera-pos hand-center-pos tick & _more] (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :rad-intensify-mark
  [_ store owner-key]
  (update (or store {:marks {}}) :marks
          (fn [marks] (into {} (remove (fn [[k _]] (= owner-key (first k)))) marks))))
