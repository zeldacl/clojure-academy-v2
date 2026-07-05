(ns cn.li.ac.content.ability.meltdowner.electron-missile-fx
  "Client FX for ElectronMissile: orbiting sparks + impact flash per fired ball."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private electron-missile-effect-id :electron-missile)

(defn default-electron-missile-fx-runtime-state
  []
  {:charge-state {}
   :beams {}
   :impacts {}})

(defn electron-missile-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot electron-missile-effect-id)
      (default-electron-missile-fx-runtime-state)))

(defn reset-electron-missile-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    electron-missile-effect-id
    (default-electron-missile-fx-runtime-state))
  nil)

(defn clear-electron-missile-owner!
  [owner-key]
  (level-effects/update-effect-state!
    electron-missile-effect-id
    (fn [store]
      (-> (or store (default-electron-missile-fx-runtime-state))
          (update :charge-state dissoc owner-key)
          (update :beams dissoc owner-key)
          (update :impacts dissoc owner-key))))
  nil)

(defn- all-impacts
  []
  (mapcat val (:impacts (electron-missile-fx-snapshot))))

(defn- all-beams
  []
  (mapcat val (:beams (electron-missile-fx-snapshot))))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
    (let [store* (or store (default-electron-missile-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
      {:keys [mode source-player-id world-id target-x target-y target-z ticks balls start end]} (or payload {})
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
          {:type :sound :sound-id "my_mod:md.em_start" :volume 0.5 :pitch 1.0})
        (assoc-in store* [:charge-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :balls 0})))
      :update
      (assoc-in store* [:charge-state owner-key*]
                (merge base-meta
                       {:active? true
                        :ticks (long (or ticks 0))
                        :balls (long (or balls 0))}))
      :fire
      (let [store* (cond
                      (and start end)
                      (update-in store* [:beams owner-key*] (fnil conj [])
                                 (merge base-meta
                                        {:start start
                                         :end end
                                         :ttl 10
                                         :max-ttl 10}))

                      (and (some? target-x) (some? target-y) (some? target-z))
                      (update-in store* [:impacts owner-key*] (fnil conj [])
                                 (merge base-meta
                                        {:target-x target-x
                                         :target-y target-y
                                         :target-z target-z
                                         :ttl 10
                                         :max-ttl 10}))

                      :else store*)]
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.em_fire" :volume 0.35 :pitch (+ 0.85 (rand 0.3))})
        store*)
      :end
      (-> store*
          (update :charge-state dissoc owner-key*)
          (update :beams dissoc owner-key*)
          (update :impacts dissoc owner-key*))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-electron-missile-fx-runtime-state))
        impacts (all-impacts)
        beams (all-beams)]
    (doseq [impact impacts]
      (when-let [tx (:target-x impact)]
        (client-particles/queue-particle-effect! (:queue-owner impact)
          {:type :particle :particle-type :electric-spark
           :x (+ (double tx) 0.5)
           :y (+ (double (:target-y impact)) 0.5)
           :z (+ (double (:target-z impact)) 0.5)
           :count 2 :speed 0.2
           :offset-x 0.25 :offset-y 0.25 :offset-z 0.25})))
    ;; Orbiting particles during charge (matching original MSG_EFFECT_UPDATE)
    (doseq [[_ st] (:charge-state store*)]
      (when (and (:active? st) (pos? (long (:balls st 0))))
        (dotimes [_ (inc (rand-int 2))]
          (let [r (+ 0.5 (rand 0.5))
                theta (rand (* 2 Math/PI))
                h (+ -1.2 (rand 1.2))]
            (client-particles/queue-particle-effect! (:queue-owner st)
              {:type :particle :particle-type :electric-spark
               :x (* r (Math/sin theta))
               :y h
               :z (* r (Math/cos theta))
               :count 1 :speed 0.05
               :offset-x 0.1 :offset-y 0.1 :offset-z 0.1})))))
    (doseq [beam beams]
      (when-let [end-pos (:end beam)]
        (client-particles/queue-particle-effect! (:queue-owner beam)
          {:type :particle :particle-type :electric-spark
           :x (double (or (:x end-pos) 0.0))
           :y (double (or (:y end-pos) 0.0))
           :z (double (or (:z end-pos) 0.0))
           :count 1 :speed 0.08
           :offset-x 0.15 :offset-y 0.15 :offset-z 0.15})))
    (-> store*
        (update :charge-state
                (fn [states]
                  (into {}
                        (keep (fn [[owner-key st]]
                                (when (:active? st)
                                  [owner-key st])))
                        states)))
        (update :beams
                (fn [by-owner]
                  (into {}
                        (keep (fn [[owner-key q]]
                                (let [live (->> q
                                                (map #(update % :ttl dec))
                                                (filter #(pos? (long (:ttl %))))
                                                vec)]
                                  (when (seq live)
                                    [owner-key live]))))
                        by-owner)))
        (update :impacts
                (fn [by-owner]
                  (into {}
                        (keep (fn [[owner-key q]]
                                (let [live (->> q
                                                (map #(update % :ttl dec))
                                                (filter #(pos? (long (:ttl %))))
                                                vec)]
                                  (when (seq live)
                                    [owner-key live]))))
                        by-owner))))))

(defn- build-plan
  [camera-pos _hand-center-pos _tick]
  (let [{:keys [beams]} (electron-missile-fx-snapshot)
        ops (mapcat (fn [[_owner-key xs]]
                      (mapcat (fn [{:keys [start end ttl max-ttl]}]
                                (let [life (/ (double ttl) (double (max 1 max-ttl)))]
                                  (ru/billboard-beam-ops camera-pos start end
                                    {:width       (* 0.04 (+ 0.3 (* 0.5 life)))
                                     :core-width  (* 0.015 (+ 0.3 (* 0.5 life)))
                                     :outer-color {:r 140 :g 255 :b 170 :a (int (+ 60 (* 140 life)))}
                                     :inner-color {:r 220 :g 255 :b 220 :a (int (+ 80 (* 150 life)))}
                                     :line-color  {:r 200 :g 255 :b 200 :a (int (+ 50 (* 120 life)))}
                                     :flicker-threshold (+ 0.6 (* 0.4 (rand)))
                                     :jitter-amount (* 0.02 life)})))
                           xs))
                    beams)]
    (when (seq ops)
      {:ops (vec ops)})))

(defn init!
  []
  (fx-spec/register!
    {:id electron-missile-effect-id
     :level {:initial-state (default-electron-missile-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :electron-missile/fx-start :mode :start}
                :update {:topic :electron-missile/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:ticks (long (or (:ticks p) 0))
                                           :balls (long (or (:balls p) 0))})}
                :fire {:topic :electron-missile/fx-fire :mode :fire
                       :level-payload (fn [_ _ p]
                                        {:start (:start p)
                                         :end (:end p)
                                         :target-x (:target-x p)
                                         :target-y (:target-y p)
                                         :target-z (:target-z p)})}
                :end {:topic :electron-missile/fx-end :mode :end}}})
  nil)
