(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.shift-teleport
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

;; Upstream STContextC marker colors: CRL_BLOCK_MARKER (139,139,139,180) for
;; the destination block, CRL_ENTITY_MARKER (235,81,81,180) per target.
(def ^:private color-block-marker {:r 139 :g 139 :b 139 :a 180})
(def ^:private color-entity-marker {:r 235 :g 81 :b 81 :a 180})

(defn- cube-edges
  "12 wireframe edges of an axis-aligned cube centered at c with half-extents."
  [c half-x half-y half-z color]
  (let [x (double (:x c)) y (double (:y c)) z (double (:z c))
        hx (double half-x) hy (double half-y) hz (double half-z)
        corners [[(- x hx) (- y hy) (- z hz)] [(+ x hx) (- y hy) (- z hz)]
                 [(+ x hx) (- y hy) (+ z hz)] [(- x hx) (- y hy) (+ z hz)]
                 [(- x hx) (+ y hy) (- z hz)] [(+ x hx) (+ y hy) (- z hz)]
                 [(+ x hx) (+ y hy) (+ z hz)] [(- x hx) (+ y hy) (+ z hz)]]
        edges [[0 1] [1 2] [2 3] [3 0]
               [4 5] [5 6] [6 7] [7 4]
               [0 4] [1 5] [2 6] [3 7]]]
    (mapv (fn [[a b]]
            (let [pa (nth corners a) pb (nth corners b)]
              (ru/line-op (rv3/v3 (double (nth pa 0)) (double (nth pa 1)) (double (nth pa 2)))
                          (rv3/v3 (double (nth pb 0)) (double (nth pb 1)) (double (nth pb 2)))
                          color)))
          edges)))

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}
        target (fn [p]
                 {:x (double (or (:x p) 0.0))
                  :y (double (or (:y p) 0.0))
                  :z (double (or (:z p) 0.0))})]
    (case (:mode payload)
      :start
      (update state* :fx-state assoc owner-key*
              (merge base-meta {:active? true :ttl 0
                                :target (target payload)
                                :target-count (long (or (:target-count payload) 0))
                                :target-hit? (boolean (:target-hit? payload))
                                :hand-valid? (boolean (if (contains? payload :hand-valid?)
                                                        (:hand-valid? payload) true))
                                :entities (vec (:entities payload))}))
      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:active? true :ttl 0}))
                       :active? true
                       :target (target payload)
                       :target-count (long (or (:target-count payload) 0))
                       :target-hit? (boolean (:target-hit? payload))
                       :hand-valid? (boolean (if (contains? payload :hand-valid?)
                                               (:hand-valid? payload) true))
                       :entities (vec (:entities payload)))))
      :perform
      (do
        ;; Burst particles at destination (upstream c_end trail endpoint).
        (when-let [x (:x payload)]
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
            {:type :particle :particle-type :portal :x (double x) :y (double (:y payload)) :z (double (:z payload))
             :count 10 :speed 0.1 :offset-x 0.6 :offset-y 0.8 :offset-z 0.6}))
        ;; Trail particles from source to destination (upstream c_end
        ;; TPParticleFactory walk, step 0.6-1.0).
        (let [fx-target {:x (double (or (:x payload) 0.0)) :y (double (or (:y payload) 0.0)) :z (double (or (:z payload) 0.0))}
              from-pos {:x (double (or (:from-x payload) (:x payload) 0.0))
                        :y (double (or (:from-y payload) (:y payload) 0.0))
                        :z (double (or (:from-z payload) (:z payload) 0.0))}
              dx (- (:x fx-target) (:x from-pos)) dy (- (:y fx-target) (:y from-pos)) dz (- (:z fx-target) (:z from-pos))
              dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
              steps (max 1 (int (/ dist 0.8)))]
          (dotimes [idx steps]
            (let [t (/ (double (inc idx)) (double steps))]
              (client-particles/queue-particle-effect! (:queue-owner base-meta)
                {:type :particle :particle-type :portal
                 :x (+ (:x from-pos) (* dx t)) :y (+ (:y from-pos) (* dy t)) :z (+ (:z from-pos) (* dz t))
                 :count 2 :speed 0.05 :offset-x 0.2 :offset-y 0.2 :offset-z 0.2}))))
        ;; Upstream c_end kills the block marker and every target marker.
        (update state* :fx-state dissoc owner-key*))
      :end
      (update state* :fx-state dissoc owner-key*)
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (let [next-st (update st :ttl (fnil inc 0))]
                    ;; Light particles at the destination as an extra cue;
                    ;; the marker cubes themselves come from build-plan.
                    (when (and (:active? next-st) (:target next-st) (:hand-valid? next-st)
                               (zero? (mod (long (:ttl next-st)) 6)))
                      (client-particles/queue-particle-effect! (:queue-owner next-st)
                        {:type :particle
                         :particle-type (if (:target-hit? next-st) :electric_spark :portal)
                         :x (double (get-in next-st [:target :x]))
                         :y (+ 0.4 (double (get-in next-st [:target :y])))
                         :z (double (get-in next-st [:target :z]))
                         :count (if (pos? (long (:target-count next-st))) 2 1)
                         :speed 0.02 :offset-x 0.25 :offset-y 0.25 :offset-z 0.25}))
                    (assoc acc owner-key next-st)))
                {} states)))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [states (vals (:fx-state (level-effects/effect-state-snapshot :shift-teleport)))
        marker-ops
        (vec
         (mapcat (fn [st]
                   (when (and (:active? st) (:hand-valid? st))
                     (concat
                       ;; Destination block marker: 1.2x1.2 (upstream
                       ;; blockMarker.width/height = 1.2f).
                       (when-let [t (:target st)]
                         (cube-edges t 0.6 0.6 0.6 color-block-marker))
                       ;; One red marker per entity in the line (upstream
                       ;; targetMarkers, refreshed every 3 ticks).
                       (mapcat (fn [e]
                                 (cube-edges e 0.25 0.25 0.25 color-entity-marker))
                               (:entities st)))))
                 states))]
    (when (seq marker-ops)
      {:ops marker-ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:shift-teleport :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:shift-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:shift-teleport :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :shift-teleport
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :shift-teleport [_ store owner-key]
  (update store :fx-state dissoc owner-key))
