(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.threatening-teleport
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]))

;; Upstream threatening-teleport marker colors (TTContextC):
;; normal (no target) 0xba,0xba,0xba,0xba — threatening (targeting) 0xba,0xb2,0x23,0x2a.
(def ^:private color-normal {:r 0xba :g 0xba :b 0xba :a 0xba})
(def ^:private color-threatening {:r 0xba :g 0xb2 :b 0x23 :a 0x2a})

(def ^:private marker-half-size 0.25)

(defn- cube-edges
  "12 wireframe edges of an axis-aligned cube centered at c (upstream
  EntityMarker is a small wireframe box the size of one block face)."
  [c]
  (let [h marker-half-size
        x (double (:x c)) y (double (:y c)) z (double (:z c))
        corners [[(- x h) (- y h) (- z h)] [(+ x h) (- y h) (- z h)]
                 [(+ x h) (- y h) (+ z h)] [(- x h) (- y h) (+ z h)]
                 [(- x h) (+ y h) (- z h)] [(+ x h) (+ y h) (- z h)]
                 [(+ x h) (+ y h) (+ z h)] [(- x h) (+ y h) (+ z h)]]
        ;; bottom ring 0-1-2-3-0, top ring 4-5-6-7-4, verticals 0-4 1-5 2-6 3-7
        rings [[0 1] [1 2] [2 3] [3 0]
               [4 5] [5 6] [6 7] [7 4]
               [0 4] [1 5] [2 6] [3 7]]]
    (mapv (fn [[a b]]
            (let [pa (nth corners a) pb (nth corners b)]
              (ru/line-op (rv3/v3 (double (nth pa 0)) (double (nth pa 1)) (double (nth pa 2)))
                          (rv3/v3 (double (nth pb 0)) (double (nth pb 1)) (double (nth pb 2)))
                          color-normal)))
          rings)))

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel
                   :source-player-id source-player-id :world-id world-id}
        aim (fn [p]
              ;; Upstream l_tick sits the marker at the TARGET's feet
              ;; (calcDropPos y = top of the box, minus target height).
              {:x (double (or (:target-x p) 0.0))
               :y (- (double (or (:target-y p) 0.0))
                     (if (:hit? p) (double (or (:target-height p) 0.0)) 0.0))
               :z (double (or (:target-z p) 0.0))})]
    (case (:mode payload)
      :start
      (update state* :fx-state assoc owner-key*
              (merge base-meta {:active? true :ttl 0
                                :aim (aim payload)
                                :hit? (boolean (:hit? payload))}))
      :update
      (update state* :fx-state update owner-key*
              (fn [st] (assoc (merge base-meta (or st {:active? true :ttl 0}))
                              :aim (aim payload)
                              :hit? (boolean (:hit? payload)))))
      :perform
      (do
        (when (:hit? payload)
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
            {:type :particle :particle-type :portal
             :x (double (or (:target-x payload) 0.0))
             :y (+ 1.0 (double (or (:target-y payload) 0.0)))
             :z (double (or (:target-z payload) 0.0))
             :count 8 :speed 0.08 :offset-x 0.3 :offset-y 0.3 :offset-z 0.3}))
        ;; AcademyCraft draws a loose teleport-particle path from the caster
        ;; to the item drop point (upstream c_end TPParticleFactory walk).
        (let [from-x (double (or (:start-x payload) 0.0))
              from-y (double (or (:start-y payload) 0.0))
              from-z (double (or (:start-z payload) 0.0))
              to-x (+ 0.5 (double (or (:target-x payload) 0.0)))
              to-y (+ 0.5 (double (or (:target-y payload) 0.0)))
              to-z (+ 0.5 (double (or (:target-z payload) 0.0)))
              dx (- to-x from-x)
              dy (- to-y from-y)
              dz (- to-z from-z)
              dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
              steps (max 1 (int (Math/ceil (/ dist 1.5))))]
          (dotimes [idx steps]
            (let [t (/ (double (inc idx)) (double steps))]
              (client-particles/queue-particle-effect! (:queue-owner base-meta)
                {:type :particle
                 :particle-type :portal
                 :x (+ from-x (* dx t))
                 :y (+ from-y (* dy t))
                 :z (+ from-z (* dz t))
                 :count 1
                 :speed 0.04
                 :offset-x 0.02
                 :offset-y 0.05
                 :offset-z 0.02}))))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.tp") :volume 0.5 :pitch 1.0})
        ;; Upstream c_end kills the marker on execute.
        (update state* :fx-state dissoc owner-key*))
      :end
      (update state* :fx-state dissoc owner-key*)
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state
            (fn [states] (reduce-kv (fn [acc k st] (assoc acc k (update st :ttl (fnil inc 0)))) {} states)))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [states (vals (:fx-state (level-effects/effect-state-snapshot :threatening-teleport)))
        marker-ops
        (vec
         (mapcat (fn [st]
                   (when-let [aim (:aim st)]
                     (let [color (if (:hit? st) color-threatening color-normal)]
                       (mapv (fn [op] (assoc op :color color)) (cube-edges aim)))))
                 states))]
    (when (seq marker-ops)
      {:ops marker-ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:threatening-teleport :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:threatening-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:threatening-teleport :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :threatening-teleport
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :threatening-teleport [_ store owner-key]
  (update store :fx-state dissoc owner-key))
