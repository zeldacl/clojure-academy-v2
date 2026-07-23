(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.meltdowner
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str])
  (:import [cn.li.mcmod.math V3]))

(defn- update-meltdowner-fx-state!
  [f & args]
  (apply level-effects/update-effect-state! :meltdowner f args))
(def ^:private charge-loop-sound (modid/namespaced-path "md.md_charge"))
(def ^:private fire-sound (modid/namespaced-path "md.meltdowner"))
(def ^:private meltdowner-ray-style
  {:width (fn [{:keys [is-reflect?]} life]
            (if is-reflect?
              (* 0.05 (+ 0.45 (* 0.55 life)))
              (* 0.09 (+ 0.6 (* 0.4 life)))))
   :core-ratio 0.42
   :outer-rgb {:r 161 :g 255 :b 142}
   :outer-alpha (fn [_ life] (int (+ 35 (* 170 life))))
   :inner-rgb {:r 244 :g 255 :b 236}
   :inner-alpha (fn [_ life] (int (+ 70 (* 170 life))))
   :line-rgb {:r 192 :g 255 :b 188}
   :line-alpha (fn [_ life] (int (+ 55 (* 150 life))))})

(defn- all-rays []
  (mapcat val (:rays (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :meltdowner))))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [store ctx-id channel owner-key payload]
  (let [store* (or store {:effect-state {} :rays {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode ticks charge-ratio performed? start end charge-ticks beam-length source-player-id world-id]} (or payload {})
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
          {:type :sound :sound-id charge-loop-sound :volume 1.0 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :charge-ratio 0.0 :performed? false})))
      :update
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta
                       (get-in store* [:effect-state owner-key*])
                       {:owner-key owner-key*
                        :ctx-id ctx-id
                        :channel channel
                        :source-player-id source-player-id
                        :world-id world-id
                        :active? true
                        :ticks (long (or ticks 0))
                        :charge-ratio (double (or charge-ratio 0.0))
                        :performed? false}))
      :end
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta
                       {:active? false :performed? (boolean performed?)
                        :ticks 0 :charge-ratio 0.0}))
      :perform
      (let [store* (if (and start end)
                      (let [life (+ 16 (rand-int 8))]
                        (update-in store* [:rays owner-key*] (fnil conj [])
                                   (merge base-meta
                                          {:start (vec3/map->v3 start) :end (vec3/map->v3 end)
                                           :ttl life :max-ttl life
                                           :beam-length (double (or beam-length 30.0))
                                           :charge-ticks (int (or charge-ticks 20))
                                           :is-reflect? false})))
                      store*)]
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id fire-sound :volume 0.5 :pitch 1.0})
        store*)
      :reflect
      (if (and start end)
        (let [life (+ 10 (rand-int 6))]
          (update-in store* [:rays owner-key*] (fnil conj [])
                     (merge base-meta
                            {:start (vec3/map->v3 start) :end (vec3/map->v3 end)
                             :ttl life :max-ttl life
                             :beam-length 10.0 :charge-ticks 20
                             :is-reflect? true})))
        store*)
      store*)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- charge-ring-segments-local
  "Ring segment endpoints relative to center — depends only on ticks (charge
  animation phase) and charge-ratio (pulse radius), both tick-rate state, so
  precomputed once per tick here rather than every frame in build-plan."
  [ticks charge-ratio]
  (let [base-radius (+ 0.72 (* 0.28 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.23 (double ticks)))))
        y-base 0.18
        ring-segments 18]
    (vec
      (for [idx (range ring-segments)
            :let [a0 (/ (* 2.0 Math/PI idx) ring-segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) ring-segments)
                  h (+ y-base (* 0.22 (Math/sin (+ (* 0.17 ticks) idx))))]]
        {:p0 {:x (* pulse (Math/cos a0)) :y h :z (* pulse (Math/sin a0))}
         :p1 {:x (* pulse (Math/cos a1)) :y h :z (* pulse (Math/sin a1))}}))))

(defn- tick-state!
  [store]
  (let [store* (or store {:effect-state {} :rays {}})
        effect-state* (into {}
                            (keep (fn [[owner-key st]]
                                    (when (:active? st)
                                      (let [ticks (inc (long (or (:ticks st) 0)))]
                                        (when (zero? (mod ticks 10))
                                          (client-sounds/queue-sound-effect! (:queue-owner st)
                                            {:type :sound :sound-id charge-loop-sound :volume 0.75 :pitch 1.0}))
                                        ;; MdParticleFactory particles (matching original: 2-3 per tick)
                                        (dotimes [_ (+ 2 (rand-int 2))]
                                          (let [r (+ 0.7 (rand 0.3))
                                                theta (rand (* 2 Math/PI))
                                                h (+ -1.2 (rand 1.2))]
                                            (client-particles/queue-particle-effect! (:queue-owner st)
                                              {:type :particle :particle-type :electric-spark
                                               :x (* r (Math/sin theta))
                                               :y h
                                               :z (* r (Math/cos theta))
                                               :count 1 :speed 0.08
                                               :offset-x 0.03 :offset-y 0.03 :offset-z 0.03
                                               :motion-x (- (rand 0.06) 0.03)
                                               :motion-y (+ 0.01 (rand 0.04))
                                               :motion-z (- (rand 0.06) 0.03)})))
                                        [owner-key (assoc st
                                                     :ticks ticks
                                                     :charge-ring-segments-local
                                                     (charge-ring-segments-local ticks (double (or (:charge-ratio st) 0.0))))]))))
                            (:effect-state store*))
        rays* (into {}
                    (keep (fn [[owner-key xs]]
                            (let [live (->> xs
                                            (map #(update % :ttl dec))
                                            (filter #(pos? (long (:ttl %))))
                                            vec)]
                              (when (seq live)
                                [owner-key live]))))
                    (:rays store*))]
    (assoc store* :effect-state effect-state* :rays rays*)))

(defn- tick!
  ([]
   (update-meltdowner-fx-state!
     (fn [store]
       (tick-state! store)))
   nil)
  ([store]
   (tick-state! store)))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- charge-ops
  "segments-local: precomputed by charge-ring-segments-local (per tick); this
  fn only translates by the live hand-center each frame."
  [^V3 center segments-local]
  (let [cx (.-x center) cy (.-y center) cz (.-z center)
        ray-color {:r 170 :g 255 :b 190 :a 170}
        link-color {:r 140 :g 240 :b 170 :a 120}]
    (vec
      (mapcat
        (fn [{:keys [p0 p1]}]
          (let [p0' (vec3/v3 (+ cx (:x p0)) (+ cy (:y p0)) (+ cz (:z p0)))
                p1' (vec3/v3 (+ cx (:x p1)) (+ cy (:y p1)) (+ cz (:z p1)))]
            [(ru/line-op p0' p1' ray-color)
             (ru/line-op center p0' link-color)]))
        segments-local))))

(defn- local-walk-speed [ticks]
  (float (max 0.001 (- 0.1 (* 0.001 (double ticks))))))

(defn- matching-active-state [hand-center-pos]
  (some (fn [st]
          (when (and (:active? st)
                     (or (nil? (:source-player-id st))
                         (nil? (:player-uuid hand-center-pos))
                         (= (str (:source-player-id st))
                            (str (:player-uuid hand-center-pos)))))
            st))
  (vals (:effect-state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :meltdowner)))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan
  "Ray :start/:end are precomputed to V3 at enqueue time (see :perform /
  :reflect above) — a ray's endpoints never change after it's fired, so
  converting once there instead of once per frame here removes an
  otherwise-per-frame allocation for every live ray."
  [camera-pos hand-center-pos _tick]
  (let [md (matching-active-state hand-center-pos)
        ^V3 cam-v (vec3/map->v3 camera-pos)
        current-rays (all-rays)
        charge-plan (if (and hand-center-pos md (:active? md) (seq (:charge-ring-segments-local md)))
                      (charge-ops (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
                                  (:charge-ring-segments-local md))
                      [])
        ws (when (and md (:active? md))
             (local-walk-speed (:ticks md)))
        ray-plan (fx-beam/fading-beams-ops cam-v current-rays meltdowner-ray-style)]
    (when (or (seq charge-plan) (seq ray-plan) ws)
      {:ops (vec (concat charge-plan ray-plan))
       :local-walk-speed ws})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:meltdowner :level] [_ _] {:effect-state {} :rays {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:meltdowner :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:meltdowner :level] [_ _ store] (tick! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :meltdowner
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :meltdowner [_ store owner-key]
  (-> store (update :effect-state dissoc owner-key) (update :rays dissoc owner-key)))
