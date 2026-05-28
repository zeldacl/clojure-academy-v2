(ns cn.li.ac.content.ability.meltdowner.meltdowner-fx
  "Client FX for Meltdowner: charge ring + beam rays + walk speed."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-meltdowner-fx-runtime-state
  []
  {:effect-state {}
   :rays {}})

(defn create-meltdowner-fx-runtime
  ([]
   (create-meltdowner-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-meltdowner-fx-runtime-state))}}]
   {::runtime ::meltdowner-fx-runtime
    :state* state*}))

(def ^:dynamic *meltdowner-fx-runtime* nil)

(defonce ^:private installed-meltdowner-fx-runtime
  (create-meltdowner-fx-runtime))

(defn- meltdowner-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::meltdowner-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-meltdowner-fx-runtime
  [runtime f]
  (when-not (meltdowner-fx-runtime? runtime)
    (throw (ex-info "Expected meltdowner FX runtime"
                    {:value runtime})))
  (binding [*meltdowner-fx-runtime* runtime]
    (f)))

(defmacro with-meltdowner-fx-runtime
  [runtime & body]
  `(call-with-meltdowner-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-meltdowner-fx-runtime
  []
  (or *meltdowner-fx-runtime*
      installed-meltdowner-fx-runtime))

(defn- meltdowner-fx-state-atom
  []
  (:state* (current-meltdowner-fx-runtime)))

(defn- meltdowner-fx-state-snapshot
  []
  @(meltdowner-fx-state-atom))

(defn- update-meltdowner-fx-state!
  [f & args]
  (apply swap! (meltdowner-fx-state-atom) f args))
(def ^:private charge-loop-sound "my_mod:md.md_charge")
(def ^:private fire-sound "my_mod:md.meltdowner")
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

(defn meltdowner-fx-snapshot []
  (meltdowner-fx-state-snapshot))

(defn reset-meltdowner-fx-for-test! []
  (reset! (meltdowner-fx-state-atom) (default-meltdowner-fx-runtime-state))
  nil)

(defn clear-meltdowner-owner! [owner-key]
  (update-meltdowner-fx-state! update :effect-state dissoc owner-key)
  (update-meltdowner-fx-state! update :rays dissoc owner-key)
  nil)

(defn- all-rays []
  (mapcat val (:rays (meltdowner-fx-state-snapshot))))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode ticks charge-ratio performed? start end charge-ticks beam-length source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (update-meltdowner-fx-state!
          update :effect-state assoc owner-key*
          (merge base-meta {:active? true :ticks 0 :charge-ratio 0.0 :performed? false}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id charge-loop-sound :volume 1.0 :pitch 1.0}))
      :update
      (update-meltdowner-fx-state!
        update :effect-state update owner-key*
        (fn [st]
          (assoc (merge base-meta (or st {}))
                 :owner-key owner-key*
                 :ctx-id ctx-id
                 :channel channel
                 :source-player-id source-player-id
                 :world-id world-id
                 :active? true
                 :ticks (long (or ticks 0))
                 :charge-ratio (double (or charge-ratio 0.0))
                 :performed? false)))
      :end
      (update-meltdowner-fx-state!
        update :effect-state assoc owner-key*
        (merge base-meta {:active? false :performed? (boolean performed?)
                          :ticks 0 :charge-ratio 0.0}))
      :perform
      (do
        (when (and start end)
          (let [life (+ 16 (rand-int 8))]
            (update-meltdowner-fx-state!
              update :rays update owner-key* (fnil conj [])
              (merge base-meta
                     {:start start :end end
                      :ttl life :max-ttl life
                      :beam-length (double (or beam-length 30.0))
                      :charge-ticks (int (or charge-ticks 20))
                      :is-reflect? false}))))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id fire-sound :volume 0.5 :pitch 1.0}))
      :reflect
      (when (and start end)
        (let [life (+ 10 (rand-int 6))]
          (update-meltdowner-fx-state!
            update :rays update owner-key* (fnil conj [])
            (merge base-meta
                   {:start start :end end
                    :ttl life :max-ttl life
                    :beam-length 10.0 :charge-ticks 20
                    :is-reflect? true}))))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-meltdowner-fx-state!
    update :effect-state
    (fn [states]
      (into {}
            (keep (fn [[owner-key st]]
                    (when (:active? st)
                      (let [ticks (inc (long (or (:ticks st) 0)))]
                        (when (zero? (mod ticks 10))
                          (client-sounds/queue-sound-effect! (:queue-owner st)
                            {:type :sound :sound-id charge-loop-sound :volume 0.75 :pitch 1.0}))
                        [owner-key (assoc st :ticks ticks)]))))
            states)))
  (update-meltdowner-fx-state!
    update :rays
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

(defn- charge-ops [center ticks charge-ratio]
  (let [base-radius (+ 0.72 (* 0.28 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.23 (double ticks)))))
        y-base (+ (double (:y center)) 0.18)
        ring-segments 18]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) ring-segments)
                a1 (/ (* 2.0 Math/PI (inc idx)) ring-segments)
                h (+ y-base (* 0.22 (Math/sin (+ (* 0.17 ticks) idx))))
                p0 {:x (+ (:x center) (* pulse (Math/cos a0)))
                    :y h
                    :z (+ (:z center) (* pulse (Math/sin a0)))}
                p1 {:x (+ (:x center) (* pulse (Math/cos a1)))
                    :y h
                    :z (+ (:z center) (* pulse (Math/sin a1)))}
                ray-color {:r 170 :g 255 :b 190 :a 170}
                link-color {:r 140 :g 240 :b 170 :a 120}]
            [(ru/line-op p0 p1 ray-color)
             (ru/line-op center p0 link-color)]))
        (range ring-segments)))))

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
        (vals (:effect-state (meltdowner-fx-state-snapshot)))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos _tick]
  (let [md (matching-active-state hand-center-pos)
        current-rays (all-rays)
        charge-plan (if (and hand-center-pos md (:active? md))
                      (let [center (dissoc hand-center-pos :player-uuid)
                            ticks (long (or (:ticks md) 0))
                            ratio (double (or (:charge-ratio md) 0.0))]
                        (charge-ops center ticks ratio))
                      [])
        ws (when (and md (:active? md))
             (local-walk-speed (:ticks md)))
           ray-plan (fx-beam/fading-beams-ops camera-pos current-rays meltdowner-ray-style)]
    (when (or (seq charge-plan) (seq ray-plan) ws)
      {:ops (vec (concat charge-plan ray-plan))
       :local-walk-speed ws})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (level-effects/register-level-effect! :meltdowner
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:meltdowner/fx-start :meltdowner/fx-update :meltdowner/fx-end
     :meltdowner/fx-perform :meltdowner/fx-reflect]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :meltdowner/fx-start
        (level-effects/enqueue-level-effect! :meltdowner (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :meltdowner/fx-update
        (level-effects/enqueue-level-effect! :meltdowner
          (merge meta-payload
                 {:mode :update
                  :ticks (long (or (:ticks payload) 0))
                  :charge-ratio (double (or (:charge-ratio payload) 0.0))})
          {:ctx-id ctx-id :channel channel})
        :meltdowner/fx-end
        (level-effects/enqueue-level-effect! :meltdowner
          (merge meta-payload {:mode :end :performed? (boolean (:performed? payload))})
          {:ctx-id ctx-id :channel channel})
        :meltdowner/fx-perform
        (level-effects/enqueue-level-effect! :meltdowner
          (merge meta-payload
                 {:mode :perform
                  :charge-ticks (int (or (:charge-ticks payload) 20))
                  :beam-length (double (or (:beam-length payload) 30.0))
                  :start (:start payload) :end (:end payload)})
          {:ctx-id ctx-id :channel channel})
        :meltdowner/fx-reflect
        (level-effects/enqueue-level-effect! :meltdowner
          (merge meta-payload {:mode :reflect :start (:start payload) :end (:end payload)})
          {:ctx-id ctx-id :channel channel})))))
  nil)
