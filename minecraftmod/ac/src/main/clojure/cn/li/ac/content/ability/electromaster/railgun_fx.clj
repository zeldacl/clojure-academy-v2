(ns cn.li.ac.content.ability.electromaster.railgun-fx
  "Client FX for Railgun: beam effects + charge hand aura."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defn default-railgun-fx-runtime-state
  []
  {:beam-effects {}})

(defn create-railgun-fx-runtime
  ([]
   (create-railgun-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-railgun-fx-runtime-state))}}]
   {::runtime ::railgun-fx-runtime
    :state* state*}))

(def ^:dynamic *railgun-fx-runtime* nil)

(defn- railgun-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::railgun-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-railgun-fx-runtime
  [runtime f]
  (when-not (railgun-fx-runtime? runtime)
    (throw (ex-info "Expected railgun FX runtime"
                    {:value runtime})))
  (binding [*railgun-fx-runtime* runtime]
    (f)))

(defmacro with-railgun-fx-runtime
  [runtime & body]
  `(call-with-railgun-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-railgun-fx-runtime
  []
  (or *railgun-fx-runtime*
      (throw (ex-info "Railgun FX runtime is not bound"
                      {:hint "Bind runtime via call-with-railgun-fx-runtime or use init! registered handlers"}))))

(defn- railgun-fx-state-atom
  []
  (:state* (current-railgun-fx-runtime)))

(defn- railgun-fx-state-snapshot
  []
  @(railgun-fx-state-atom))

(defn- update-railgun-fx-state!
  [f & args]
  (apply swap! (railgun-fx-state-atom) f args))

(def ^:private beam-life-ticks 12)
(def ^:private railgun-beam-style
  {:width (fn [_ life] (* 0.08 (+ 0.5 life)))
   :core-ratio 0.45
   :outer-rgb {:r 236 :g 170 :b 93}
   :outer-alpha (fn [_ life] (+ 50 (* 150 life)))
   :inner-rgb {:r 241 :g 240 :b 222}
   :inner-alpha (fn [_ life] (+ 80 (* 150 life)))
   :line-rgb {:r 165 :g 230 :b 255}
   :line-alpha (fn [_ life] (+ 40 (* 120 life)))})

(defn railgun-fx-snapshot
  []
  (railgun-fx-state-snapshot))

(defn reset-railgun-fx-for-test!
  []
  (reset! (railgun-fx-state-atom) (default-railgun-fx-runtime-state))
  nil)

(defn clear-railgun-owner!
  [owner-key]
  (update-railgun-fx-state! update :beam-effects dissoc owner-key)
  nil)

(defn- all-beam-effects []
  (mapcat val (:beam-effects (railgun-fx-state-snapshot))))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode start end hit-distance source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (when (and start end)
      (update-railgun-fx-state!
        update :beam-effects update owner-key* (fnil conj [])
        (merge base-meta
               {:start start
                :end end
                :mode (or mode :block-hit)
                :hit-distance (double (or hit-distance 18.0))
                :ttl beam-life-ticks
                :max-ttl beam-life-ticks})))))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-railgun-fx-state!
    update :beam-effects
    (fn [by-owner]
      (into {}
            (keep (fn [[owner-key xs]]
                    (let [live (->> xs
                                    (map #(update % :ttl dec))
                                    (filter #(pos? (long (:ttl %))))
                                    vec)]
                      (when (seq live)
                        [owner-key live]))))
            by-owner))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- impact-ring-ops [end ttl max-ttl]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        radius (+ 0.12 (* 0.22 (- 1.0 life)))
        color (ru/with-alpha {:r 188 :g 252 :b 238} (+ 20 (* 160 life)))
        segments 12]
    (vec
      (for [idx (range segments)
            :let [t0 (/ (* 2.0 Math/PI idx) segments)
                  t1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x end) (* radius (Math/cos t0))) :y (:y end) :z (+ (:z end) (* radius (Math/sin t0)))}
                  p1 {:x (+ (:x end) (* radius (Math/cos t1))) :y (:y end) :z (+ (:z end) (* radius (Math/sin t1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- charge-hand-ops [center charge-ratio coin-active? tick]
  (let [radius (+ 0.11 (* 0.12 (double charge-ratio)))
        alpha (if coin-active? 210 170)
        pulse (+ radius (* 0.02 (Math/sin (* 0.25 tick))))
        points 14]
    (vec
      (mapcat
        (fn [idx]
          (let [t0 (/ (* 2.0 Math/PI idx) points)
                t1 (/ (* 2.0 Math/PI (inc idx)) points)
                p0 {:x (+ (:x center) (* pulse (Math/cos t0)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin t0)))}
                p1 {:x (+ (:x center) (* pulse (Math/cos t1)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin t1)))}]
            [(ru/line-op p0 p1 {:r 120 :g 220 :b 255 :a alpha})
             (ru/line-op center p0 {:r 90 :g 190 :b 255 :a 120})]))
        (range points)))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos tick]
  (let [beams (all-beam-effects)
        player-uuid (:player-uuid hand-center-pos)
        charge-state (when player-uuid
                       (client-runtime/railgun-charge-visual-state player-uuid))
        beam-plan (mapcat (fn [beam]
                            (concat
                              (fx-beam/fading-beam-ops camera-pos beam railgun-beam-style)
                              (impact-ring-ops (:end beam) (:ttl beam) (:max-ttl beam))))
                          beams)
        charge-plan (if (and hand-center-pos (:active? charge-state))
                      (charge-hand-ops (dissoc hand-center-pos :player-uuid)
                                       (:charge-ratio charge-state)
                                       (:coin-active? charge-state)
                                       tick)
                      [])]
    (when (or (seq beam-plan) (seq charge-plan))
      {:ops (vec (concat beam-plan charge-plan))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (let [runtime (create-railgun-fx-runtime)]
    (level-effects/register-level-effect! :railgun-shot
      {:enqueue-event-fn (fn [event]
                           (call-with-railgun-fx-runtime
                             runtime
                             (fn []
                               (enqueue! event))))
       :tick-fn (fn []
                  (call-with-railgun-fx-runtime
                    runtime
                    tick!))
       :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:railgun/fx-shot :railgun/fx-reflect]
    (fn [ctx-id channel payload]
      (level-effects/enqueue-level-effect! :railgun-shot payload
                                           {:ctx-id ctx-id :channel channel})))
  nil)
