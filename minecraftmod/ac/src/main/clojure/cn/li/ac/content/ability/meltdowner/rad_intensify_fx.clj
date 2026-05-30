(ns cn.li.ac.content.ability.meltdowner.rad-intensify-fx
  "Client-side visual hint for rad-intensify target marks."
  (:require [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private rad-intensify-effect-id :rad-intensify-mark)

(defn default-rad-intensify-fx-runtime-state
  []
  {:marks {}})

(defn rad-intensify-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot rad-intensify-effect-id)
      (default-rad-intensify-fx-runtime-state)))

(defn reset-rad-intensify-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    rad-intensify-effect-id
    (default-rad-intensify-fx-runtime-state))
  nil)

(defn- mark-key
  [{:keys [owner-key target-id]}]
  [owner-key (str target-id)])

(defn- enqueue-state!
  [store {:keys [payload ctx-id owner-key]}]
  (let [store* (or store (default-rad-intensify-fx-runtime-state))
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
  (let [store* (or store (default-rad-intensify-fx-runtime-state))]
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
            (ru/line-op {:x cx :y cy :z cz}
                        {:x (+ cx dx)
                         :y (+ cy 0.2)
                         :z (+ cz dz)}
                        {:r 255 :g 120 :b 40 :a alpha})))))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  (let [ops (->> (:marks (rad-intensify-fx-snapshot))
                 vals
                 (mapcat mark-ops)
                 vec)]
    (when (seq ops)
      {:ops ops})))

(defn init!
  []
  (level-effects/register-level-effect! rad-intensify-effect-id
    {:initial-state (default-rad-intensify-fx-runtime-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:rad-intensify/fx-mark]
    (fn [ctx-id channel payload]
      (when (= channel :rad-intensify/fx-mark)
        (level-effects/enqueue-level-effect! rad-intensify-effect-id payload
                                             {:ctx-id ctx-id :channel channel}))))
  nil)
