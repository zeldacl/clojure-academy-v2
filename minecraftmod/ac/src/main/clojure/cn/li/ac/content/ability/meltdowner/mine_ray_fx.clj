(ns cn.li.ac.content.ability.meltdowner.mine-ray-fx
  "Client FX for all mine-ray variants: beam glow + block progress indicator."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-mine-ray-fx-runtime-state
  []
  {:effect-state {}})

(defn create-mine-ray-fx-runtime
  ([]
   (create-mine-ray-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-mine-ray-fx-runtime-state))}}]
   {::runtime ::mine-ray-fx-runtime
    :state* state*}))

(def ^:dynamic *mine-ray-fx-runtime* nil)

(defonce ^:private installed-mine-ray-fx-runtime
  (create-mine-ray-fx-runtime))

(defn- mine-ray-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::mine-ray-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-mine-ray-fx-runtime
  [runtime f]
  (when-not (mine-ray-fx-runtime? runtime)
    (throw (ex-info "Expected mine-ray FX runtime"
                    {:value runtime})))
  (binding [*mine-ray-fx-runtime* runtime]
    (f)))

(defmacro with-mine-ray-fx-runtime
  [runtime & body]
  `(call-with-mine-ray-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-mine-ray-fx-runtime
  []
  (or *mine-ray-fx-runtime*
      installed-mine-ray-fx-runtime))

(defn- mine-ray-fx-state-atom
  []
  (:state* (current-mine-ray-fx-runtime)))

(defn- mine-ray-fx-state-snapshot
  []
  @(mine-ray-fx-state-atom))

(defn- update-mine-ray-fx-state!
  [f & args]
  (apply swap! (mine-ray-fx-state-atom) f args))

(defn mine-ray-fx-snapshot []
  (mine-ray-fx-state-snapshot))

(defn reset-mine-ray-fx-for-test! []
  (reset! (mine-ray-fx-state-atom) (default-mine-ray-fx-runtime-state))
  nil)

(defn clear-mine-ray-owner! [owner-key]
  (update-mine-ray-fx-state! update :effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode variant x y z progress source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (update-mine-ray-fx-state!
          update :effect-state assoc owner-key*
          (merge base-meta {:active? true :ticks 0 :variant (or variant :basic)
                            :target nil :progress 0.0}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.mine_ray_start" :volume 0.5 :pitch 1.0}))
      :progress
      (update-mine-ray-fx-state!
        update :effect-state update owner-key*
        (fn [st]
          (when st
            (assoc (merge base-meta st)
                   :owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id
                   :target {:x (int (or x 0)) :y (int (or y 0)) :z (int (or z 0))}
                   :progress (double (or progress 0.0))))))
      :end
      (clear-mine-ray-owner! owner-key*)
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-mine-ray-fx-state!
    update :effect-state
    (fn [states]
      (into {}
            (keep (fn [[owner-key st]]
                    (when (:active? st)
                      (let [ticks (inc (long (or (:ticks st) 0)))]
                        (when (zero? (mod ticks 8))
                          (when-let [target (:target st)]
                            (client-particles/queue-particle-effect! (:queue-owner st)
                              {:type :particle :particle-type :electric-spark
                               :x (+ (double (:x target)) 0.5)
                               :y (+ (double (:y target)) 0.5)
                               :z (+ (double (:z target)) 0.5)
                               :count 2 :speed 0.1
                               :offset-x 0.3 :offset-y 0.3 :offset-z 0.3})))
                        [owner-key (assoc st :ticks ticks)]))))
            states))))

;; ---------------------------------------------------------------------------
;; Render ops  
;; ---------------------------------------------------------------------------

(defn- progress-box-ops [target progress ticks variant]
  (let [x (double (:x target))
        y (double (:y target))
        z (double (:z target))
        ;; Color based on variant
        c (case variant
            :luck    {:r 255 :g 215 :b 0   :a 220}
            :expert  {:r 100 :g 255 :b 100 :a 200}
            {:r 150 :g 220 :b 255 :a 180})
        alpha (int (* (:a c) (+ 0.5 (* 0.5 (Math/sin (* 0.3 (double ticks)))))))
        col (assoc c :a alpha)
        ;; Shrink box based on progress (0→1)
        shrink (* 0.05 (- 1.0 progress))
        x0 (+ x shrink) y0 (+ y shrink) z0 (+ z shrink)
        x1 (- (+ x 1.0) shrink) y1 (- (+ y 1.0) shrink) z1 (- (+ z 1.0) shrink)]
    [(ru/line-op {:x x0 :y y0 :z z0} {:x x1 :y y0 :z z0} col)
     (ru/line-op {:x x1 :y y0 :z z0} {:x x1 :y y0 :z z1} col)
     (ru/line-op {:x x1 :y y0 :z z1} {:x x0 :y y0 :z z1} col)
     (ru/line-op {:x x0 :y y0 :z z1} {:x x0 :y y0 :z z0} col)
     (ru/line-op {:x x0 :y y1 :z z0} {:x x1 :y y1 :z z0} col)
     (ru/line-op {:x x1 :y y1 :z z0} {:x x1 :y y1 :z z1} col)
     (ru/line-op {:x x1 :y y1 :z z1} {:x x0 :y y1 :z z1} col)
     (ru/line-op {:x x0 :y y1 :z z1} {:x x0 :y y1 :z z0} col)
     (ru/line-op {:x x0 :y y0 :z z0} {:x x0 :y y1 :z z0} col)
     (ru/line-op {:x x1 :y y0 :z z0} {:x x1 :y y1 :z z0} col)
     (ru/line-op {:x x1 :y y0 :z z1} {:x x1 :y y1 :z z1} col)
     (ru/line-op {:x x0 :y y0 :z z1} {:x x0 :y y1 :z z1} col)]))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [ops (mapcat (fn [st]
                      (when (and (:active? st) (:target st))
                        (progress-box-ops (:target st)
                                          (double (or (:progress st) 0.0))
                                          (long (or (:ticks st) 0))
                                          (or (:variant st) :basic))))
                      (vals (:effect-state (mine-ray-fx-state-snapshot))))]
    (when (seq ops)
      {:ops (vec ops)})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (level-effects/register-level-effect! :mine-ray
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:mine-ray/fx-start :mine-ray/fx-progress :mine-ray/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :mine-ray/fx-start
        (level-effects/enqueue-level-effect! :mine-ray
          (merge meta-payload {:mode :start :variant (:variant payload)})
          {:ctx-id ctx-id :channel channel})
        :mine-ray/fx-progress
        (level-effects/enqueue-level-effect! :mine-ray
          (merge meta-payload
                 {:mode :progress
                  :x (:x payload) :y (:y payload) :z (:z payload)
                  :progress (:progress payload)})
          {:ctx-id ctx-id :channel channel})
        :mine-ray/fx-end
        (level-effects/enqueue-level-effect! :mine-ray (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
