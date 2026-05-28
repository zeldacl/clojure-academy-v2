(ns cn.li.ac.content.ability.meltdowner.ray-barrage-fx
  "Client FX for RayBarrage skill: multi-beam flash with electric afterimages."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-ray-barrage-fx-runtime-state
  []
  {:beam-queue {}})

(defn create-ray-barrage-fx-runtime
  ([]
   (create-ray-barrage-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-ray-barrage-fx-runtime-state))}}]
   {::runtime ::ray-barrage-fx-runtime
    :state* state*}))


(defonce ^:private installed-ray-barrage-fx-runtime
  (create-ray-barrage-fx-runtime))

(defonce ^:private ray-barrage-fx-runtime-override* (atom nil))

(defn- ray-barrage-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::ray-barrage-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-ray-barrage-fx-runtime
  [runtime f]
  (when-not (ray-barrage-fx-runtime? runtime)
    (throw (ex-info "Expected ray barrage FX runtime"
                    {:value runtime})))
  (let [prev-override @ray-barrage-fx-runtime-override*]
    (try
      (reset! ray-barrage-fx-runtime-override* runtime)
      (f)
      (finally
        (reset! ray-barrage-fx-runtime-override* prev-override)))))

(defmacro with-ray-barrage-fx-runtime
  [runtime & body]
  `(call-with-ray-barrage-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-ray-barrage-fx-runtime
  []
  (or @ray-barrage-fx-runtime-override*
      @installed-ray-barrage-fx-runtime))

(defn- ray-barrage-fx-state-atom
  []
  (:state* (current-ray-barrage-fx-runtime)))

(defn- ray-barrage-fx-state-snapshot
  []
  @(ray-barrage-fx-state-atom))

(defn- update-ray-barrage-fx-state!
  [f & args]
  (apply swap! (ray-barrage-fx-state-atom) f args))

(defn ray-barrage-fx-snapshot []
  (ray-barrage-fx-state-snapshot))

(defn reset-ray-barrage-fx-for-test! []
  (reset! (ray-barrage-fx-state-atom) (default-ray-barrage-fx-runtime-state))
  nil)

(defn clear-ray-barrage-owner! [owner-key]
  (update-ray-barrage-fx-state! update :beam-queue dissoc owner-key)
  nil)

(defn- all-beams []
  (mapcat val (:beam-queue (ray-barrage-fx-state-snapshot))))

(defn- enqueue-beam! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (update-ray-barrage-fx-state!
      update :beam-queue update owner-key*
      (fn [q]
        (let [q* (vec q)
              q2 (conj (if (> (count q*) 10) (subvec q* (- (count q*) 10)) q*)
                       (merge base-meta payload {:ttl 12}))]
          q2)))))

(defn- tick! []
  (update-ray-barrage-fx-state!
    update :beam-queue
    (fn [by-owner]
      (into {}
            (keep (fn [[owner-key q]]
                    (let [live (->> q
                                    (map (fn [b] (update b :ttl dec)))
                                    (filter (fn [b] (pos? (:ttl b))))
                                    vec)]
                      (when (seq live)
                        [owner-key live]))))
            by-owner))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (when-let [beams (seq (all-beams))]
    {:ops (mapcat
            (fn [beam]
              (let [{:keys [from-x from-y from-z to-x to-y to-z ttl]} beam
                    alpha (int (* 180 (/ (double ttl) 12.0)))
                    col {:r 255 :g 100 :b 50 :a alpha}]
                [(ru/line-op {:x (double from-x) :y (double from-y) :z (double from-z)}
                             {:x (double to-x)   :y (double to-y)   :z (double to-z)}
                             col)]))
            beams)}))

(defn init! []
  (let [runtime (create-ray-barrage-fx-runtime)]
    (level-effects/register-level-effect! :ray-barrage
      {:enqueue-event-fn (fn [event]
                           (call-with-ray-barrage-fx-runtime
                             runtime
                             (fn []
                               (enqueue-beam! event))))
       :tick-fn (fn []
                  (call-with-ray-barrage-fx-runtime
                    runtime
                    tick!))
       :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:ray-barrage/fx-beam]
    (fn [ctx-id channel payload]
      (when (= channel :ray-barrage/fx-beam)
        (when-let [beam-end (:beam-end payload)]
          (let [origin (:origin payload)
                meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
            (level-effects/enqueue-level-effect! :ray-barrage
              (merge meta-payload
                     {:from-x (:x origin) :from-y (:y origin) :from-z (:z origin)
                      :to-x   (:x beam-end) :to-y (:y beam-end) :to-z (:z beam-end)})
              {:ctx-id ctx-id :channel channel})
            (client-sounds/queue-current-sound-effect!
              {:type :sound :sound-id "my_mod:md.ray_barrage" :volume 0.4 :pitch (+ 0.9 (rand 0.2))}))))))
  nil)
