(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.flesh-ripping
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private stale-owner-ttl-ticks 80)

;; Upstream FRContextC marker colors: DISABLED (74,74,74,160) with no target,
;; THREATENING (185,25,25,180) when an entity is targeted.
(def ^:private color-disabled {:r 74 :g 74 :b 74 :a 160})
(def ^:private color-threatening {:r 185 :g 25 :b 25 :a 180})

(defn- spawn-blood-splash!
  "Spawn EntityBloodSplash at target (matching original EntityBloodSplash on
  hit — the spec is follow-owner? false with a 10-tick life, so spawnLocalAt
  leaves it where it lands)."
  [x y z]
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect-at
    {:effect-id "entity_blood_splash" :x x :y y :z z}))

(defn- marker-cube-ops
  "Wireframe cube centered at c. Upstream l_updateEffect sizes the marker by
  the target (width*1.2 / height*1.2) or 1.0x1.0 without one."
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
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}
        aim (fn [p]
              {:x (double (or (:target-x p) 0.0))
               :y (double (or (:target-y p) 0.0))
               :z (double (or (:target-z p) 0.0))})]
    (case (:mode payload)
      :start
      (update state* :fx-state assoc owner-key*
              (merge base-meta {:active? true :ttl 0
                                :aim (aim payload)
                                :hit? (boolean (:hit? payload))
                                :target-uuid (:target-uuid payload)
                                :target-width (double (or (:target-width payload) 0.6))
                                :target-height (double (or (:target-height payload) 1.8))}))

      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:active? true :ttl 0}))
                       :active? true
                       :ttl 0
                       :aim (aim payload)
                       :hit? (boolean (:hit? payload))
                       :target-uuid (:target-uuid payload)
                       :target-width (double (or (:target-width payload) 0.6))
                       :target-height (double (or (:target-height payload) 1.8)))))

      :perform
      (do
        (when (:hit? payload)
          (let [x (double (or (:entity-x payload) (:target-x payload) 0.0))
                y (double (or (:entity-y payload) (:target-y payload) 0.0))
                z (double (or (:entity-z payload) (:target-z payload) 0.0))
                width (double (or (:target-width payload) 0.6))
                height (double (or (:target-height payload) 1.8))
                splash-count (+ 4 (rand-int 3))]
            (dotimes [_ splash-count]
              (let [theta (* (rand) Math/PI 2.0)
                    radius (* 0.5 width (+ 0.8 (* 0.2 (rand))))]
                (spawn-blood-splash!
                  (+ x (* radius (Math/sin theta)))
                  (+ y (* height (rand)))
                  (+ z (* radius (Math/cos theta))))))))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.guts") :volume 0.6 :pitch 1.0})
        ;; Upstream c_endEffect kills the marker on MSG_EFFECT_END.
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
                    (if (> (long (:ttl next-st)) stale-owner-ttl-ticks)
                      acc
                      (assoc acc owner-key next-st))))
                {}
                states)))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [states (vals (:fx-state (level-effects/effect-state-snapshot :flesh-ripping)))
        marker-ops
        (vec
         (mapcat (fn [st]
                   (when-let [aim (:aim st)]
                     (let [hit? (:hit? st)
                           color (if hit? color-threatening color-disabled)
                           half-x (if hit?
                                    (* 0.5 (double (or (:target-width st) 0.6)) 1.2)
                                    0.5)
                           half-y (if hit?
                                    (* 0.5 (double (or (:target-height st) 1.8)) 1.2)
                                    0.5)]
                       (marker-cube-ops aim half-x half-y half-x color))))
                 states))]
    (when (seq marker-ops)
      {:ops marker-ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:flesh-ripping :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:flesh-ripping :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:flesh-ripping :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :flesh-ripping
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :flesh-ripping [_ store owner-key]
  (update store :fx-state dissoc owner-key))
