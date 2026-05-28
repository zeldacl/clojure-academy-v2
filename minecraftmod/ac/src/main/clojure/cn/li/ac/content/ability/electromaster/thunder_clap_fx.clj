(ns cn.li.ac.content.ability.electromaster.thunder-clap-fx
  "Client FX for Thunder Clap: surround ring + target mark + walk speed."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]))

(defn default-thunder-clap-fx-runtime-state
  []
  {:effect-state {}})

(defn create-thunder-clap-fx-runtime
  ([]
   (create-thunder-clap-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-thunder-clap-fx-runtime-state))}}]
   {::runtime ::thunder-clap-fx-runtime
    :state* state*}))

(def ^:dynamic *thunder-clap-fx-runtime* nil)

(defn- thunder-clap-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::thunder-clap-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-thunder-clap-fx-runtime
  [runtime f]
  (when-not (thunder-clap-fx-runtime? runtime)
    (throw (ex-info "Expected thunder clap FX runtime"
                    {:value runtime})))
  (binding [*thunder-clap-fx-runtime* runtime]
    (f)))

(defmacro with-thunder-clap-fx-runtime
  [runtime & body]
  `(call-with-thunder-clap-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-thunder-clap-fx-runtime
  []
  (or *thunder-clap-fx-runtime*
      (throw (ex-info "Thunder Clap FX runtime is not bound"
                      {:hint "Bind runtime via call-with-thunder-clap-fx-runtime or use init! registered handlers"}))))

(defn- thunder-clap-fx-state-atom
  []
  (:state* (current-thunder-clap-fx-runtime)))

(defn- thunder-clap-fx-state-snapshot
  []
  @(thunder-clap-fx-state-atom))

(defn- update-thunder-clap-fx-state!
  [f & args]
  (apply swap! (thunder-clap-fx-state-atom) f args))

(defn thunder-clap-fx-snapshot
  []
  (thunder-clap-fx-state-snapshot))

(defn reset-thunder-clap-fx-for-test!
  []
  (reset! (thunder-clap-fx-state-atom) (default-thunder-clap-fx-runtime-state))
  nil)

(defn clear-thunder-clap-owner!
  [owner-key]
  (update-thunder-clap-fx-state! update :effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode ticks charge-ratio target performed? source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (update-thunder-clap-fx-state!
        update :effect-state assoc owner-key*
        (merge base-meta {:active? true :ticks 0 :charge-ratio 0.0 :target nil :performed? false}))
      :update
      (update-thunder-clap-fx-state!
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
                 :target target)))
      :end
      (update-thunder-clap-fx-state!
        update :effect-state assoc owner-key*
        (merge base-meta {:active? false :performed? (boolean performed?)
                          :ticks 0 :charge-ratio 0.0 :target nil}))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-thunder-clap-fx-state!
    update :effect-state
    (fn [states]
      (into {}
            (keep (fn [[owner-key st]]
                    (when (:active? st)
                      [owner-key (update st :ticks (fnil inc 0))])))
            states))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- surround-ops [player-center ticks]
  (let [radius (+ 0.55 (* 0.25 (Math/sin (* 0.22 (double ticks)))))
        y (+ (double (:y player-center)) 0.2)
        segments 20
        color {:r 190 :g 232 :b 255 :a 170}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x player-center) (* radius (Math/cos a0)))
                      :y y
                      :z (+ (:z player-center) (* radius (Math/sin a0)))}
                  p1 {:x (+ (:x player-center) (* radius (Math/cos a1)))
                      :y y
                      :z (+ (:z player-center) (* radius (Math/sin a1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- target-mark-ops [target ticks charge-ratio]
  (let [base-radius (+ 0.55 (* 0.35 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.24 (double ticks)))))
        y (+ (double (:y target)) 0.03)
        segments 24
        color {:r 204 :g 204 :b 204 :a 179}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x target) (* pulse (Math/cos a0)))
                      :y y
                      :z (+ (:z target) (* pulse (Math/sin a0)))}
                  p1 {:x (+ (:x target) (* pulse (Math/cos a1)))
                      :y y
                      :z (+ (:z target) (* pulse (Math/sin a1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- local-walk-speed [ticks]
  (let [max-speed 0.1
        min-speed 0.001
        value (- max-speed (* (/ (- max-speed min-speed) 60.0) (double ticks)))]
    (float (max min-speed value))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos hand-center-pos _tick]
  (let [tc (some (fn [st]
                   (when (and (:active? st)
                              (or (nil? (:source-player-id st))
                                  (nil? (:player-uuid hand-center-pos))
                                  (= (str (:source-player-id st))
                                     (str (:player-uuid hand-center-pos)))))
                     st))
                 (vals (:effect-state (thunder-clap-fx-state-snapshot))))]
    (when (and hand-center-pos tc (:active? tc))
      (let [player-center (dissoc hand-center-pos :player-uuid)
            ticks (long (or (:ticks tc) 0))
            ratio (double (or (:charge-ratio tc) 0.0))
            ops (vec (concat
                       (surround-ops player-center ticks)
                       (when (map? (:target tc))
                         (target-mark-ops (:target tc) ticks ratio))))
            ws (local-walk-speed ticks)]
        (when (or (seq ops) ws)
          {:ops ops
           :local-walk-speed ws})))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (let [runtime (create-thunder-clap-fx-runtime)]
    (level-effects/register-level-effect! :thunder-clap
      {:enqueue-event-fn (fn [event]
                           (call-with-thunder-clap-fx-runtime
                             runtime
                             (fn []
                               (enqueue! event))))
       :tick-fn (fn []
                  (call-with-thunder-clap-fx-runtime
                    runtime
                    tick!))
       :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:thunder-clap/fx-start :thunder-clap/fx-update :thunder-clap/fx-end]
    (fn [ctx-id channel payload]
      (case channel
        :thunder-clap/fx-start
        (level-effects/enqueue-level-effect! :thunder-clap
                                             (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                                                    {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :thunder-clap/fx-update
        (level-effects/enqueue-level-effect! :thunder-clap
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :update
                  :ticks (long (or (:ticks payload) 0))
                  :charge-ratio (double (or (:charge-ratio payload) 0.0))
                  :target (get payload :target)})
          {:ctx-id ctx-id :channel channel})
        :thunder-clap/fx-end
        (level-effects/enqueue-level-effect! :thunder-clap
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :end
                  :performed? (boolean (:performed? payload))})
          {:ctx-id ctx-id :channel channel}))))
  nil)
