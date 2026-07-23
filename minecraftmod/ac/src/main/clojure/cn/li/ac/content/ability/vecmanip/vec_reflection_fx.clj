(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx
  "Client FX for VecReflection: double ring + reflection wave billboards."
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru])
  (:import [cn.li.mcmod.math V3]))

(def ^:private vec-reflection-effect-id :vec-reflection)
(def ^:private sound-id (modid/namespaced-path "vecmanip.vec_reflection"))

(defn default-vec-reflection-fx-runtime-state
  []
  {:effect-state {}
   :wave-effects {}})

(defn vec-reflection-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot vec-reflection-effect-id)
      (default-vec-reflection-fx-runtime-state)))

(defn reset-vec-reflection-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    vec-reflection-effect-id
    (default-vec-reflection-fx-runtime-state))
  nil)

(defn clear-vec-reflection-owner!
  [owner-key]
  (level-effects/update-effect-state!
    vec-reflection-effect-id
    (fn [state]
      (-> (or state (default-vec-reflection-fx-runtime-state))
          (update :effect-state dissoc owner-key)
          (update :wave-effects dissoc owner-key))))
  nil)

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-vec-reflection-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z reflected? source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? true :ticks 0}))
      :end
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? false :ticks 0}))
      :reflect-entity
      (if reflected?
        (let [life (+ 8 (rand-int 6))]
          (update-in store* [:wave-effects owner-key*]
            (fnil conj [])
            (merge base-meta
                   {:x (double (or x 0.0))
                    :y (double (or y 0.0))
                    :z (double (or z 0.0))
                    :ttl life
                    :max-ttl life})))
        store*)
      :play
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
           :x (double (or x 0.0)) :y (double (or y 0.0)) :z (double (or z 0.0))})
        store*)
      store*)))

(defn- tick-state!
  [store]
  (let [state* (or store (default-vec-reflection-fx-runtime-state))]
    (assoc state*
           :effect-state
           (into {}
                 (keep (fn [[owner-key st]]
                         (when (:active? st)
                           [owner-key (update st :ticks (fnil inc 0))])))
                 (:effect-state state*))
           :wave-effects
           (into {}
                 (keep (fn [[owner-key xs]]
                         (let [live (->> xs
                                         (map #(update % :ttl dec))
                                         (filter #(pos? (long (:ttl %))))
                                         vec)]
                           (when (seq live)
                             [owner-key live]))))
                 (:wave-effects state*)))))

(defn- matching-active-state
  [effect-state hand-center-pos]
  (some (fn [st]
          (when (and (:active? st)
                     (or (nil? (:source-player-id st))
                         (nil? (:player-uuid hand-center-pos))
                         (= (str (:source-player-id st))
                            (str (:player-uuid hand-center-pos)))))
            st))
        (vals effect-state)))

(defn- double-ring-ops
  "`center` crosses in as a map (dissoc'd hand-center-pos) — convert once."
  [center ticks]
  (let [^V3 c (vec3/map->v3 center)
        cx (.-x c) cz (.-z c)
        base-angle (* 0.15 (double ticks))
        outer-r (+ 0.9 (* 0.06 (Math/sin (* 0.2 (double ticks)))))
        inner-r (* outer-r 0.55)
        y-outer (+ (.-y c) 0.2)
        y-inner (+ (.-y c) 0.35)
        segments 28
        outer-color {:r 255 :g 210 :b 180 :a 160}
        inner-color {:r 255 :g 180 :b 140 :a 130}]
    (vec
      (concat
        (for [i (range segments)
              :let [a0 (+ base-angle (/ (* 2.0 Math/PI i) segments))
                    a1 (+ base-angle (/ (* 2.0 Math/PI (inc i)) segments))]]
          (ru/line-op
            (vec3/v3 (+ cx (* outer-r (Math/cos a0))) y-outer (+ cz (* outer-r (Math/sin a0))))
            (vec3/v3 (+ cx (* outer-r (Math/cos a1))) y-outer (+ cz (* outer-r (Math/sin a1))))
            outer-color))
        (for [i (range segments)
              :let [a0 (- (- base-angle) (/ (* 2.0 Math/PI i) segments))
                    a1 (- (- base-angle) (/ (* 2.0 Math/PI (inc i)) segments))]]
          (ru/line-op
            (vec3/v3 (+ cx (* inner-r (Math/cos a0))) y-inner (+ cz (* inner-r (Math/sin a0))))
            (vec3/v3 (+ cx (* inner-r (Math/cos a1))) y-inner (+ cz (* inner-r (Math/sin a1))))
            inner-color))))))

(defn- wave-ops
  "`cam-pos` crosses in as a map (from the shared level-effect-plan context);
  x/y/z here are raw doubles stored in the in-memory wave-effects record —
  convert once at the top of the per-frame computation."
  [cam-pos {:keys [x y z ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        alpha (int (max 0 (min 255 (* 180.0 life))))
        ^V3 cam (vec3/map->v3 cam-pos)
        center (vec3/v3 (double x) (+ (double y) 0.6) (double z))
        right (ru/camera-facing-right-axis center cam)
        up (ru/billboard-up-axis center cam right)
        half-size (+ 0.4 (* 0.6 (- 1.0 life)))
        side (vec3/v* right half-size)
        lift (vec3/v* up half-size)
        p0 (vec3/v+ (vec3/v- center side) lift)
        p1 (vec3/v+ (vec3/v+ center side) lift)
        p2 (vec3/v- (vec3/v+ center side) lift)
        p3 (vec3/v- (vec3/v- center side) lift)]
    [(ru/quad-op (modid/asset-path "textures" "effects/glow_circle.png")
                 p0 p1 p2 p3
                 {:r 255 :g 200 :b 160 :a alpha})]))

(defn- build-plan
  [camera-pos hand-center-pos _tick _query-fn]
  (let [{:keys [effect-state wave-effects]} (vec-reflection-fx-snapshot)
        vr (matching-active-state effect-state hand-center-pos)
        current-waves (mapcat val wave-effects)
        ring-plan (if (and hand-center-pos vr (:active? vr))
                    (double-ring-ops (dissoc hand-center-pos :player-uuid)
                                     (long (or (:ticks vr) 0)))
                    [])
        wave-plan (mapcat #(wave-ops camera-pos %) current-waves)]
    (when (or (seq ring-plan) (seq wave-plan))
      {:ops (vec (concat ring-plan wave-plan))})))

(defn init!
  []
  (fx-spec/register!
    {:id vec-reflection-effect-id
     :level {:initial-state (default-vec-reflection-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :vec-reflection/fx-start :mode :start}
                :end {:topic :vec-reflection/fx-end :mode :end}
                :reflect-entity {:topic :vec-reflection/fx-reflect-entity :mode :reflect-entity
                                 :level-payload (fn [_ _ p]
                                                  {:x (double (or (:x p) 0.0))
                                                   :y (double (or (:y p) 0.0))
                                                   :z (double (or (:z p) 0.0))
                                                   :reflected? (boolean (:reflected? p))})}
                :play {:topic :vec-reflection/fx-play :mode :play
                       :level-payload (fn [_ _ p]
                                        {:x (double (or (:x p) 0.0))
                                         :y (double (or (:y p) 0.0))
                                         :z (double (or (:z p) 0.0))})}}})
  nil)
