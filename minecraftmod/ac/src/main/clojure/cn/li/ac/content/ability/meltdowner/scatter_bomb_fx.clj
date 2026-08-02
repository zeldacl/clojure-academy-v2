(ns cn.li.ac.content.ability.meltdowner.scatter-bomb-fx
  "Client FX for ScatterBomb: ball spawn + scatter beam flashes.

  The release beams render as billboard quads from each ball's position to
  its scattered destination (original SBNetDelegate: EntityMdRaySmall
  setFromTo(ball.getPositionEyes, dest)) — a fixed-direction ray entity
  spawned at the player instead pointed wherever its rotation happened to be."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]))

(def ^:private scatter-bomb-effect-id :scatter-bomb)

(defn default-scatter-bomb-fx-runtime-state
  []
  {:effect-state {} :beams {}})

(defn scatter-bomb-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot scatter-bomb-effect-id)
      (default-scatter-bomb-fx-runtime-state)))

(defn reset-scatter-bomb-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    scatter-bomb-effect-id
    (default-scatter-bomb-fx-runtime-state))
  nil)

(defn clear-scatter-bomb-owner!
  [owner-key]
  (level-effects/update-effect-state!
    scatter-bomb-effect-id
    (fn [store]
      (-> (or store (default-scatter-bomb-fx-runtime-state))
          (update :effect-state dissoc owner-key)
          (update :beams dissoc owner-key))))
  nil)

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-scatter-bomb-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z count start end source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id (modid/namespaced-path "md.sb_charge") :volume 0.5 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :balls 0})))
      :ball
      (do
        (client-particles/queue-current-particle-effect!
          {:type :particle :particle-type :electric-spark
           :x (double (or x 0.0))
           :y (double (or y 0.0))
           :z (double (or z 0.0))
           :count 4 :speed 0.1
           :offset-x 0.3 :offset-y 0.3 :offset-z 0.3})
        (update-in store* [:effect-state owner-key*]
          (fn [st]
            (assoc (merge base-meta (or st {:active? true :ticks 0}))
                   :owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id
                   :balls (int (or count 0))))))
      :beam
      (let [store* (if (and start end)
                     (update-in store* [:beams owner-key*] (fnil conj [])
                                {:start (vec3/map->v3 start)
                                 :end (vec3/map->v3 end)
                                 ;; Original EntityMdRaySmall: life 14 ticks.
                                 :ttl 14
                                 :max-ttl 14})
                     store*)]
        (when (and start end)
          (client-particles/queue-current-particle-effect!
            {:type :particle :particle-type :electric-spark
             :x (double (or (:x end) 0.0))
             :y (double (or (:y end) 0.0))
             :z (double (or (:z end) 0.0))
             :count 4 :speed 0.15
             :offset-x 0.4 :offset-y 0.4 :offset-z 0.4}))
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id (modid/namespaced-path "md.eb_explode") :volume 0.4 :pitch 1.2})
        store*)
      :end
      (-> store*
          (update :effect-state dissoc owner-key*)
          (update :beams dissoc owner-key*))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-scatter-bomb-fx-runtime-state))]
    (-> store*
        (update :effect-state
          (fn [states]
            (into {}
                  (keep (fn [[owner-key st]]
                          (when (:active? st)
                            [owner-key (assoc st :ticks (inc (long (or (:ticks st) 0))))])))
                  states)))
        (update :beams
          (fn [by-owner]
            (into {}
                  (keep (fn [[owner-key beams]]
                          (let [live (->> beams
                                          (map #(update % :ttl dec))
                                          (filter #(pos? (long (:ttl %))))
                                          vec)]
                            (when (seq live)
                              [owner-key live]))))
                  by-owner))))))

(defn- beam-flash-ops
  "Green ray quads from ball position to dest, matching original
  EntityMdRaySmall colors (inner 0.03 rgba(216,248,216,230), outer 0.045
  rgba(106,242,106,50)) with a fade-in/out over the 14-tick life."
  [camera-pos beams]
  (mapcat (fn [{:keys [start end ttl max-ttl]}]
            (let [life-ratio (if (pos? (or max-ttl 1)) (/ (or ttl 1.0) (double max-ttl)) 1.0)
                  blend-in  (min 1.0 (/ (max 0 (- 1.0 life-ratio)) 0.28))
                  blend-out (min 1.0 (/ life-ratio 0.57))
                  am (* blend-in blend-out)
                  shrink (if (< life-ratio 0.3) 0.0 1.0)]
              (ru/billboard-beam-ops camera-pos start end
                {:width 0.3
                 :core-width 0.045
                 :core-ratio 0.667
                 :outer-color {:r 106 :g 242 :b 106 :a (int (* 50 am shrink))}
                 :inner-color {:r 106 :g 242 :b 106 :a (int (* 128 am shrink))}
                 :line-color  {:r 216 :g 248 :b 216 :a (int (* 230 am shrink))}})))
          beams))

(defn- build-plan
  [camera-pos _hand-center-pos _tick & _query-fn]
  (let [snapshot (or (level-effects/effect-state-snapshot scatter-bomb-effect-id)
                     (default-scatter-bomb-fx-runtime-state))
        beams (get snapshot :beams)]
    (when (seq beams)
      (let [cam-v (vec3/map->v3 camera-pos)
            ops (vec (mapcat (fn [[_owner-key xs]] (beam-flash-ops cam-v xs)) beams))]
        (when (seq ops)
          {:ops ops})))))

(defn init!
  []
  (fx-spec/register!
    {:id scatter-bomb-effect-id
     :level {:initial-state (default-scatter-bomb-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :scatter-bomb/fx-start :mode :start}
                :ball {:topic :scatter-bomb/fx-ball :mode :ball
                       :level-payload (fn [_ _ p]
                                        {:x (:x p) :y (:y p) :z (:z p) :count (:count p)})}
                :beam {:topic :scatter-bomb/fx-beam :mode :beam
                       :level-payload (fn [_ _ p]
                                        {:start (:start p) :end (:end p)})}
                :end {:topic :scatter-bomb/fx-end :mode :end}}})
  nil)
