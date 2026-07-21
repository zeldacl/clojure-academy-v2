(ns cn.li.ac.content.ability.meltdowner.mine-ray-fx
  "Client FX for all mine-ray variants: beam glow + block progress indicator."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private mine-ray-effect-id :mine-ray)

(defn- start-sound-id
  [variant]
  (case variant
    :expert (modid/namespaced-path "md.mine_expert_startup")
    :luck (modid/namespaced-path "md.mine_luck_startup")
    (modid/namespaced-path "md.mine_basic_startup")))

(defn default-mine-ray-fx-runtime-state
  []
  {:effect-state {}})

(defn mine-ray-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot mine-ray-effect-id)
      (default-mine-ray-fx-runtime-state)))

(defn reset-mine-ray-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    mine-ray-effect-id
    (default-mine-ray-fx-runtime-state))
  nil)

(defn clear-mine-ray-owner!
  [owner-key]
  (level-effects/update-effect-state!
    mine-ray-effect-id
    (fn [store]
      (update (or store (default-mine-ray-fx-runtime-state)) :effect-state dissoc owner-key)))
  nil)

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-mine-ray-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode variant x y z progress source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound
           :sound-id (start-sound-id variant)
           :volume 0.5
           :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :variant (or variant :basic)
                                    :target nil :progress 0.0})))
      :progress
      (if-let [st (get-in store* [:effect-state owner-key*])]
        (assoc-in store* [:effect-state owner-key*]
                  (assoc (merge base-meta st)
                         :owner-key owner-key*
                         :ctx-id ctx-id
                         :channel channel
                         :source-player-id source-player-id
                         :world-id world-id
                         :target {:x (int (or x 0)) :y (int (or y 0)) :z (int (or z 0))}
                         :progress (double (or progress 0.0))))
        store*)
      :end
      (update store* :effect-state dissoc owner-key*)
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-mine-ray-fx-runtime-state))]
    (update store* :effect-state
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
              states)))))

(defn- progress-box-ops
  [target progress ticks variant]
  (let [x (double (:x target))
        y (double (:y target))
        z (double (:z target))
        c (case variant
            :luck {:r 255 :g 215 :b 0 :a 220}
            :expert {:r 100 :g 255 :b 100 :a 200}
            {:r 150 :g 220 :b 255 :a 180})
        alpha (int (* (:a c) (+ 0.5 (* 0.5 (Math/sin (* 0.3 (double ticks)))))))
        col (assoc c :a alpha)
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

(defn- build-plan
  [_camera-pos _hand-center-pos _tick & _more]
  (let [ops (mapcat (fn [st]
                      (when (and (:active? st) (:target st))
                        (progress-box-ops (:target st)
                                          (double (or (:progress st) 0.0))
                                          (long (or (:ticks st) 0))
                                          (or (:variant st) :basic))))
                    (vals (:effect-state (mine-ray-fx-snapshot))))]
    (when (seq ops)
      {:ops (vec ops)})))

(defn init!
  []
  (fx-spec/register!
    {:id mine-ray-effect-id
     :level {:initial-state (default-mine-ray-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :mine-ray/fx-start :mode :start
                        :level-payload (fn [_ _ p] {:variant (:variant p)})}
                :progress {:topic :mine-ray/fx-progress :mode :progress
                           :level-payload (fn [_ _ p]
                                            {:x (:x p) :y (:y p) :z (:z p)
                                             :progress (:progress p)})}
                :end {:topic :mine-ray/fx-end :mode :end}}})
  nil)
