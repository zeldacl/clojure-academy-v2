(ns cn.li.ac.content.ability.electromaster.mine-detect-fx
  "Client FX for MineDetect: local ore scan + textured highlight rendering."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(defn default-mine-detect-fx-runtime-state
  []
  {:effect-state {}})

(defn create-mine-detect-fx-runtime
  ([]
   (create-mine-detect-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-mine-detect-fx-runtime-state))}}]
   {::runtime ::mine-detect-fx-runtime
    :state* state*}))


(defonce ^:private installed-mine-detect-fx-runtime
  (create-mine-detect-fx-runtime))

(defonce ^:private mine-detect-fx-runtime-override* (atom nil))

(defn- mine-detect-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::mine-detect-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-mine-detect-fx-runtime
  [runtime f]
  (when-not (mine-detect-fx-runtime? runtime)
    (throw (ex-info "Expected mine detect FX runtime"
                    {:value runtime})))
  (let [prev-override @mine-detect-fx-runtime-override*]
    (try
      (reset! mine-detect-fx-runtime-override* runtime)
      (f)
      (finally
        (reset! mine-detect-fx-runtime-override* prev-override)))))

(defmacro with-mine-detect-fx-runtime
  [runtime & body]
  `(call-with-mine-detect-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-mine-detect-fx-runtime
  []
  (or @mine-detect-fx-runtime-override*
      @installed-mine-detect-fx-runtime))

(defn- mine-detect-fx-state-atom
  []
  (:state* (current-mine-detect-fx-runtime)))

(defn- mine-detect-fx-state-snapshot
  []
  @(mine-detect-fx-state-atom))

(defn- update-mine-detect-fx-state!
  [f & args]
  (apply swap! (mine-detect-fx-state-atom) f args))

(defn mine-detect-fx-snapshot
  []
  (mine-detect-fx-state-snapshot))

(defn reset-mine-detect-fx-for-test!
  []
  (reset! (mine-detect-fx-state-atom) (default-mine-detect-fx-runtime-state))
  nil)

(defn clear-mine-detect-owner!
  [owner-key]
  (update-mine-detect-fx-state! update :effect-state dissoc owner-key)
  nil)

(def ^:private mineview-texture
  "my_mod:textures/effects/mineview.png")

(def ^:private default-life-ticks 100)
(def ^:private default-rescan-interval 5)
(def ^:private max-client-range 28.0)

(def ^:private default-ore-color
  {:r 220 :g 235 :b 255 :a 185})

(def ^:private advanced-tier-colors
  {0 {:r 205 :g 225 :b 255 :a 165}
   1 {:r 255 :g 210 :b 95 :a 190}
   2 {:r 255 :g 120 :b 120 :a 210}})

(defn- ore-block-id?
  [block-id]
  (and (string? block-id)
       (or (str/includes? block-id "_ore")
           (str/includes? block-id "ancient_debris"))))

(defn- clamped-range
  [range]
  (double (max 1.0 (min max-client-range (double (or range 20.0))))))

(defn- fallback-advanced-tier
  [block-id]
  (cond
    (or (str/includes? block-id "diamond")
        (str/includes? block-id "emerald")
        (str/includes? block-id "ancient_debris")) 2

    (or (str/includes? block-id "gold")
        (str/includes? block-id "redstone")
        (str/includes? block-id "lapis")) 1

    :else 0))

(defn- advanced-tier
  [{:keys [harvest-level block-id]}]
  (if (number? harvest-level)
    (cond
      (>= (long harvest-level) 3) 2
      (>= (long harvest-level) 2) 1
      :else 0)
    (fallback-advanced-tier (str block-id))))

(defn- ore-color
  [ore advanced?]
  (if advanced?
    (get advanced-tier-colors (advanced-tier ore) default-ore-color)
    default-ore-color))

(defn- faded-color
  [color ticks life-ticks]
  (let [progress (if (pos? life-ticks)
                   (min 1.0 (/ (double ticks) (double life-ticks)))
                   1.0)
        alpha-scale (max 0.2 (- 1.0 progress))]
    (ru/with-alpha color (int (* (double (:a color)) alpha-scale)))))

(defn- block-highlight-ops
  [x y z color]
  (let [eps 0.02
        x0 (- (double x) eps)
        y0 (- (double y) eps)
        z0 (- (double z) eps)
        x1 (+ (double x) 1.0 eps)
        y1 (+ (double y) 1.0 eps)
        z1 (+ (double z) 1.0 eps)]
    [(ru/quad-op mineview-texture {:x x0 :y y0 :z z0} {:x x1 :y y0 :z z0} {:x x1 :y y1 :z z0} {:x x0 :y y1 :z z0} color)
     (ru/quad-op mineview-texture {:x x1 :y y0 :z z1} {:x x0 :y y0 :z z1} {:x x0 :y y1 :z z1} {:x x1 :y y1 :z z1} color)
     (ru/quad-op mineview-texture {:x x0 :y y0 :z z1} {:x x0 :y y0 :z z0} {:x x0 :y y1 :z z0} {:x x0 :y y1 :z z1} color)
     (ru/quad-op mineview-texture {:x x1 :y y0 :z z0} {:x x1 :y y0 :z z1} {:x x1 :y y1 :z z1} {:x x1 :y y1 :z z0} color)
     (ru/quad-op mineview-texture {:x x0 :y y1 :z z0} {:x x1 :y y1 :z z0} {:x x1 :y y1 :z z1} {:x x0 :y y1 :z z1} color)
     (ru/quad-op mineview-texture {:x x0 :y y0 :z z1} {:x x1 :y y0 :z z1} {:x x1 :y y0 :z z0} {:x x0 :y y0 :z z0} color)]))

(defn- should-rescan?
  [{:keys [ticks rescan-interval last-rescan-tick]}]
  (or (nil? last-rescan-tick)
      (>= (- (long ticks) (long last-rescan-tick))
          (long (max 1 (or rescan-interval default-rescan-interval))))))

(defn- rescan-ores
  [{:keys [range]} hand-center-pos frame-context]
  (let [query-fn (:query-nearby-blocks frame-context)]
    (if (and (fn? query-fn) (map? hand-center-pos))
      (let [origin-x (double (or (:x hand-center-pos) 0.0))
            origin-y (double (or (:y hand-center-pos) 64.0))
            origin-z (double (or (:z hand-center-pos) 0.0))
            r (clamped-range range)]
        (->> (query-fn origin-x origin-y origin-z r ore-block-id?)
             (map (fn [block]
                    {:x (int (:x block))
                     :y (int (:y block))
                     :z (int (:z block))
                   :block-id (:block-id block)
                   :harvest-level (:harvest-level block)}))
             (distinct)
             (vec)))
      [])))

(defn- apply-perform!
  [owner-key ctx-id channel {:keys [range advanced? life-ticks rescan-interval source-player-id world-id]}]
  (client-sounds/queue-current-sound-effect!
    {:type :sound
     :sound-id "my_mod:em.minedetect"
     :volume 0.8
     :pitch 1.0})
  (update-mine-detect-fx-state!
    update :effect-state assoc owner-key
    {:owner-key owner-key
     :ctx-id ctx-id
     :channel channel
     :source-player-id source-player-id
     :world-id world-id
     :active? true
     :ticks 0
     :life-ticks (long (max 1 (or life-ticks default-life-ticks)))
     :rescan-interval (long (max 1 (or rescan-interval default-rescan-interval)))
     :last-rescan-tick nil
     :range (clamped-range range)
     :advanced? (boolean advanced?)
     :ores []}))

(defn- enqueue!
  [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])]
    (case (:mode payload)
      :perform (apply-perform! owner-key* ctx-id channel payload)
      :end (update-mine-detect-fx-state! update :effect-state dissoc owner-key*)
      nil)))

(defn- tick!
  []
  (update-mine-detect-fx-state!
    update :effect-state
    (fn [states]
      (reduce-kv (fn [acc owner-key st]
                   (if (:active? st)
                     (let [next-ticks (inc (long (:ticks st)))
                           life-ticks (long (:life-ticks st))]
                       (if (< next-ticks life-ticks)
                         (assoc acc owner-key (assoc st :ticks next-ticks))
                         acc))
                     acc))
                 {}
                 states))))

(defn- maybe-refresh-ores!
  [owner-key hand-center-pos frame-context]
  (update-mine-detect-fx-state!
    update :effect-state
    (fn [states]
      (update states owner-key
              (fn [st]
                (if (and st (should-rescan? st))
                  (assoc st
                         :ores (rescan-ores st hand-center-pos frame-context)
                         :last-rescan-tick (:ticks st))
                  st))))))

(defn- build-plan
  [_camera-pos hand-center-pos _tick frame-context]
  (when-let [[owner-key _] (some (fn [[owner-key st]]
                                   (when (:active? st)
                                     [owner-key st]))
                                 (:effect-state (mine-detect-fx-state-snapshot)))]
    (maybe-refresh-ores! owner-key hand-center-pos frame-context)
    (let [{:keys [ticks life-ticks ores advanced?]} (get (:effect-state (mine-detect-fx-state-snapshot)) owner-key)
          ops (into []
                    (mapcat (fn [{:keys [x y z] :as ore}]
                              (let [base-color (ore-color ore advanced?)
                                    color (faded-color base-color ticks life-ticks)]
                                (block-highlight-ops x y z color))))
                    ores)]
      (when (seq ops)
        {:ops ops}))))

(defn init!
  []
  (let [runtime (create-mine-detect-fx-runtime)]
    (level-effects/register-level-effect! :mine-detect
                                          {:enqueue-event-fn (fn [event]
                                                               (call-with-mine-detect-fx-runtime
                                                                 runtime
                                                                 (fn []
                                                                   (enqueue! event))))
                                           :tick-fn (fn []
                                                      (call-with-mine-detect-fx-runtime
                                                        runtime
                                                        tick!))
                                           :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:mine-detect/fx-perform :mine-detect/fx-end]
    (fn [ctx-id channel payload]
      (case channel
        :mine-detect/fx-perform
        (level-effects/enqueue-level-effect! :mine-detect
                                             {:mode :perform
                                              :range (:range payload)
                                              :advanced? (:advanced? payload)
                                              :life-ticks (:life-ticks payload)
                                              :rescan-interval (:rescan-interval payload)}
                                             {:ctx-id ctx-id :channel channel})
        :mine-detect/fx-end
        (level-effects/enqueue-level-effect! :mine-detect {:mode :end}
                                             {:ctx-id ctx-id :channel channel})
        nil)))
  nil)
