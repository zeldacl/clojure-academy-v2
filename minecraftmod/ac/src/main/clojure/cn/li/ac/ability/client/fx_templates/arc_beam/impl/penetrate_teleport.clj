(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.penetrate-teleport
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

;; Upstream EntityTPMarking renders grey when the destination is unavailable
;; (mark.available = dest.available).
(def ^:private color-available {:r 230 :g 236 :b 255 :a 180})
(def ^:private color-unavailable {:r 74 :g 74 :b 74 :a 120})

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      :start
      (update state* :fx-state assoc owner-key*
              (merge base-meta {:active? true :ttl 0
                                :x (double (or (:x payload) 0.0))
                                :y (double (or (:y payload) 0.0))
                                :z (double (or (:z payload) 0.0))
                                :available? (boolean (:available? payload))
                                :distance (double (or (:distance payload) 0.0))}))
      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:active? true :ttl 0}))
                       :active? true
                       :ttl 0
                       :x (double (or (:x payload) 0.0))
                       :y (double (or (:y payload) 0.0))
                       :z (double (or (:z payload) 0.0))
                       :available? (boolean (:available? payload))
                       :distance (double (or (:distance payload) 0.0)))))
      :perform
      (do
        (client-particles/queue-particle-effect! (:queue-owner base-meta)
          {:type :particle :particle-type :portal
           :x (double (or (:to-x payload) 0.0)) :y (+ 1.0 (double (or (:to-y payload) 0.0))) :z (double (or (:to-z payload) 0.0))
           :count 10 :speed 0.06 :offset-x 0.25 :offset-y 0.5 :offset-z 0.25})
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.tp") :volume 0.5 :pitch 1.0})
        ;; Upstream c_endEffect kills the mark on MSG_TERMINATED.
        (update state* :fx-state dissoc owner-key*))
      :end
      (update state* :fx-state dissoc owner-key*)
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state (fn [states] (reduce-kv (fn [acc k st] (assoc acc k (update st :ttl (fnil inc 0)))) {} states)))))

(defn- ground-ring-ops [x y z distance available?]
  (let [radius (+ 0.45 (* 0.05 (min 1.0 (/ (double distance) 30.0))))
        color (if available? color-available color-unavailable)
        segments 24]
    (vec (for [idx (range segments)
               :let [a0 (/ (* 2.0 Math/PI idx) segments)
                     a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                     p0 (rv3/v3 (+ x (* radius (Math/cos a0))) (+ y 0.02) (+ z (* radius (Math/sin a0))))
                     p1 (rv3/v3 (+ x (* radius (Math/cos a1))) (+ y 0.02) (+ z (* radius (Math/sin a1))))]]
           (ru/line-op p0 p1 color)))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [states (vals (:fx-state (level-effects/effect-state-snapshot :penetrate-teleport)))
        marker-ops
        (vec
         (mapcat (fn [st]
                   (when (:active? st)
                     (ground-ring-ops (double (or (:x st) 0.0))
                                      (double (or (:y st) 0.0))
                                      (double (or (:z st) 0.0))
                                      (double (or (:distance st) 0.0))
                                      (:available? st))))
                 states))]
    (when (seq marker-ops)
      {:ops marker-ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:penetrate-teleport :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:penetrate-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:penetrate-teleport :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :penetrate-teleport
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :penetrate-teleport [_ store owner-key]
  (update store :fx-state dissoc owner-key))
