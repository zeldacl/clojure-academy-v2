(ns cn.li.ac.content.ability.vecmanip.blood-retrograde-fx
  "Client FX for Blood Retrograde: splashes, sprays, walk speed."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(defonce ^:private splashes (atom []))
(defonce ^:private sprays (atom []))
(def ^:private sound-id "my_mod:vecmanip.blood_retro")
(def ^:private splash-life 10)
(def ^:private spray-life 1200)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode ticks charge-ratio performed? sound-pos]} payload]
    (case mode
      :start
      (reset! effect-state {:active? true :ticks 0 :charge-ratio 0.0 :performed? false})
      :update
      (swap! effect-state
             (fn [st]
               (assoc (or st {})
                      :active? true
                      :ticks (long (or ticks 0))
                      :charge-ratio (double (or charge-ratio 0.0))
                      :performed? false)))
      :perform
      (do
        (swap! splashes into
               (map (fn [splash]
                      (assoc splash :ttl splash-life :max-ttl splash-life))
                    (:splashes payload)))
        (swap! sprays into
               (map (fn [spray]
                      (assoc spray :ttl spray-life :max-ttl spray-life))
                    (:sprays payload)))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id sound-id :volume 1.0 :pitch 1.0
           :x (:x sound-pos) :y (:y sound-pos) :z (:z sound-pos)}))
      :end
      (reset! effect-state {:active? false :ticks 0 :charge-ratio 0.0
                            :performed? (boolean performed?)})
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when st
             (if (:active? st)
               (update st :ticks (fnil inc 0))
               nil))))
  (swap! splashes
         (fn [xs] (->> xs (map #(update % :ttl dec)) (filter #(pos? (long (:ttl %)))) vec)))
  (swap! sprays
         (fn [xs] (->> xs (map #(update % :ttl dec)) (filter #(pos? (long (:ttl %)))) vec))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- splash-ops [cam-pos {:keys [x y z size ttl max-ttl]}]
  (let [center {:x (double x) :y (double y) :z (double z)}
        half-size (* 0.5 (double (or size 1.0)))
        right (ru/camera-facing-right-axis center cam-pos)
        up (ru/billboard-up-axis center cam-pos right)
        side (ru/v* right half-size)
        lift (ru/v* up half-size)
        p0 (ru/v+ (ru/v- center side) lift)
        p1 (ru/v+ (ru/v+ center side) lift)
        p2 (ru/v- (ru/v+ center side) lift)
        p3 (ru/v- (ru/v- center side) lift)
        age (long (- (long (or max-ttl splash-life)) (long (or ttl splash-life))))
        frame (max 0 (min 9 age))]
    [(ru/quad-op (str "my_mod:textures/effects/blood_splash/" frame ".png")
                 p0 p1 p2 p3
                 {:r 213 :g 29 :b 29 :a 200})]))

(defn- spray-face-basis [face]
  (case face
    :up    [{:x 0.0 :y 1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0}]
    :down  [{:x 0.0 :y -1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z -1.0}]
    :north [{:x 0.0 :y 0.0 :z -1.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 1.0 :z 0.0}]
    :south [{:x 0.0 :y 0.0 :z 1.0} {:x -1.0 :y 0.0 :z 0.0} {:x 0.0 :y 1.0 :z 0.0}]
    :west  [{:x -1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z -1.0} {:x 0.0 :y 1.0 :z 0.0}]
    :east  [{:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0} {:x 0.0 :y 1.0 :z 0.0}]
    [{:x 0.0 :y 1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0}]))

(defn- spray-ops [{:keys [x y z face size rotation offset-u offset-v texture-id ttl]}]
  (let [[normal tangent bitangent] (spray-face-basis face)
        center (ru/v+
                 {:x (+ (double x) 0.5)
                  :y (+ (double y) 0.5)
                  :z (+ (double z) 0.5)}
                 (ru/v* normal 0.51))
        tangent' (ru/rotate-around-axis tangent normal (double (or rotation 0.0)))
        bitangent' (ru/rotate-around-axis bitangent normal (double (or rotation 0.0)))
        shifted (ru/v+ center
                       (ru/v+ (ru/v* tangent' (double (or offset-u 0.0)))
                              (ru/v* bitangent' (double (or offset-v 0.0)))))
        half-size (* 0.5 (double (or size 1.0)))
        side (ru/v* tangent' half-size)
        lift (ru/v* bitangent' half-size)
        p0 (ru/v+ (ru/v- shifted side) lift)
        p1 (ru/v+ (ru/v+ shifted side) lift)
        p2 (ru/v- (ru/v+ shifted side) lift)
        p3 (ru/v- (ru/v- shifted side) lift)
        life (if (and ttl (< ttl 60)) (/ (double ttl) 60.0) 1.0)
        tex-folder (if (contains? #{:up :down} face) "wall" "grnd")
        tex-index (max 0 (min 2 (long (or texture-id 0))))]
    [(ru/quad-op (str "my_mod:textures/effects/blood_spray/" tex-folder "/" tex-index ".png")
                 p0 p1 p2 p3
                 {:r 255 :g 255 :b 255 :a (int (+ 40 (* 180 life)))})]))

(defn- local-walk-speed [ticks]
  (let [ratio (min 1.0 (/ (double ticks) 20.0))]
    (float (+ 0.1 (* (- 0.007 0.1) ratio)))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [br @effect-state
        current-splashes @splashes
        current-sprays @sprays
        splash-plan (mapcat #(splash-ops camera-pos %) current-splashes)
        spray-plan (mapcat spray-ops current-sprays)
        ws (when (and br (:active? br))
             (local-walk-speed (:ticks br)))]
    (when (or (seq splash-plan) (seq spray-plan) ws)
      {:ops (vec (concat splash-plan spray-plan))
       :local-walk-speed ws})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :blood-retrograde
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:blood-retrograde/fx-start :blood-retrograde/fx-update
   :blood-retrograde/fx-end :blood-retrograde/fx-perform]
  (fn [_ctx-id channel payload]
    (case channel
      :blood-retrograde/fx-start
      (level-effects/enqueue-level-effect! :blood-retrograde {:mode :start})
      :blood-retrograde/fx-update
      (level-effects/enqueue-level-effect! :blood-retrograde
        {:mode :update
         :ticks (long (or (:ticks payload) 0))
         :charge-ratio (double (or (:charge-ratio payload) 0.0))})
      :blood-retrograde/fx-end
      (level-effects/enqueue-level-effect! :blood-retrograde
        {:mode :end :performed? (boolean (:performed? payload))})
      :blood-retrograde/fx-perform
      (level-effects/enqueue-level-effect! :blood-retrograde
        {:mode :perform
         :sound-pos (:sound-pos payload)
         :splashes (:splashes payload)
         :sprays (:sprays payload)}))))
