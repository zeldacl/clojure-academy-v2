(ns cn.li.ac.content.ability.electromaster.thunder-clap-fx
  "Client FX for Thunder Clap: surround ring + target mark + walk speed."
  (:require [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private thunder-clap-effect-id :thunder-clap)

(defn default-thunder-clap-fx-runtime-state
  []
  {:effect-state {}
   :impacts {}})

(defn thunder-clap-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot thunder-clap-effect-id)
      (default-thunder-clap-fx-runtime-state)))

(defn reset-thunder-clap-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    thunder-clap-effect-id
    (default-thunder-clap-fx-runtime-state))
  nil)

(defn clear-thunder-clap-owner!
  [owner-key]
  (level-effects/update-effect-state!
    thunder-clap-effect-id
    (fn [store]
      (let [store* (or store (default-thunder-clap-fx-runtime-state))]
        (-> store*
            (update :effect-state dissoc owner-key)
            (update :impacts dissoc owner-key)))))
  nil)

(defn- enqueue-state!
  [store event]
  (let [store* (or store (default-thunder-clap-fx-runtime-state))
        {:keys [payload ctx-id channel owner-key]} event
        {:keys [mode ticks charge-ratio target performed? source-player-id world-id]} (or payload {})
        owner-key* (or owner-key [:ctx ctx-id])
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}
        effect-state (:effect-state store*)
        current-st (get effect-state owner-key*)]
    (case mode
      :start
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta
                       {:active? true
                        :ticks 0
                        :charge-ratio 0.0
                        :target nil
                        :performed? false}))

      :update
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta
                       current-st
                       {:active? true
                        :ticks (long (or ticks 0))
                        :charge-ratio (double (or charge-ratio 0.0))
                        :target target}))

      :perform
      (let [next-store (assoc-in store* [:effect-state owner-key*]
                                 (merge base-meta
                                        current-st
                                        {:active? true
                                         :ticks (long (or ticks (:ticks current-st) 0))
                                         :charge-ratio (double (or charge-ratio (:charge-ratio current-st) 0.0))
                                         :target (or target (:target current-st))
                                         :performed? true}))]
        (if (map? target)
          (update-in next-store [:impacts owner-key*] (fnil conj [])
                     (merge base-meta
                            {:target target
                             :ttl 6
                             :max-ttl 6
                             :charge-ratio (double (or charge-ratio 0.0))}))
          next-store))

      :end
      (let [next-store (update store* :effect-state dissoc owner-key*)]
        (if (and (map? target) performed?)
          (update-in next-store [:impacts owner-key*] (fnil conj [])
                     (merge base-meta
                            {:target target
                             :ttl 4
                             :max-ttl 4
                             :charge-ratio (double (or charge-ratio 0.0))}))
          next-store))

      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-thunder-clap-fx-runtime-state))]
    (-> store*
        (update :effect-state
          (fn [states]
            (into {}
                  (keep (fn [[owner-key st]]
                          (when (:active? st)
                            [owner-key (update st :ticks (fnil inc 0))])))
                  states)))
        (update :impacts
          (fn [by-owner]
            (into {}
                  (keep (fn [[owner-key impacts]]
                          (let [live (->> impacts
                                          (map #(update % :ttl dec))
                                          (filter #(pos? (long (or (:ttl %) 0))))
                                          vec)]
                            (when (seq live)
                              [owner-key live]))))
                  by-owner))))))

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

(defn- impact-ops [{:keys [target ttl max-ttl charge-ratio]}]
  (let [life (if (pos? (long (or max-ttl 0)))
               (/ (double (or ttl 0)) (double max-ttl))
               0.0)
        growth (- 1.0 life)
        radius (+ 0.8 (* 0.65 growth) (* 0.2 (double (or charge-ratio 0.0))))
        y (+ (double (:y target)) 0.08)
        segments 20
        alpha (int (+ 20 (* 160 life)))
        color {:r 220 :g 245 :b 255 :a alpha}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x target) (* radius (Math/cos a0)))
                      :y y
                      :z (+ (:z target) (* radius (Math/sin a0)))}
                  p1 {:x (+ (:x target) (* radius (Math/cos a1)))
                      :y y
                      :z (+ (:z target) (* radius (Math/sin a1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- local-walk-speed [ticks]
  (let [max-speed 0.1
        min-speed 0.001
        value (- max-speed (* (/ (- max-speed min-speed) 60.0) (double ticks)))]
    (float (max min-speed value))))

(defn- build-plan [_camera-pos hand-center-pos _tick]
  (let [{:keys [effect-state impacts]} (thunder-clap-fx-snapshot)
        tc (some (fn [st]
                   (when (and (:active? st)
                              (or (nil? (:source-player-id st))
                                  (nil? (:player-uuid hand-center-pos))
                                  (= (str (:source-player-id st))
                                     (str (:player-uuid hand-center-pos)))))
                     st))
                 (vals effect-state))
        charge-ops (if (and hand-center-pos tc (:active? tc))
                     (let [player-center (dissoc hand-center-pos :player-uuid)
                           ticks (long (or (:ticks tc) 0))
                           ratio (double (or (:charge-ratio tc) 0.0))]
                       (vec (concat
                              (surround-ops player-center ticks)
                              (when (map? (:target tc))
                                (target-mark-ops (:target tc) ticks ratio)))))
                     [])
        impact-render-ops (vec (mapcat impact-ops (mapcat val impacts)))
        ws (when (and tc (:active? tc))
             (local-walk-speed (:ticks tc)))]
    (when (or (seq charge-ops) (seq impact-render-ops) ws)
      {:ops (vec (concat charge-ops impact-render-ops))
       :local-walk-speed ws})))

(defn init!
  []
  (level-effects/register-level-effect! thunder-clap-effect-id
    {:initial-state (default-thunder-clap-fx-runtime-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:thunder-clap/fx-start :thunder-clap/fx-update :thunder-clap/fx-perform :thunder-clap/fx-end]
    (fn [ctx-id channel payload]
      (case channel
        :thunder-clap/fx-start
        (level-effects/enqueue-level-effect! thunder-clap-effect-id
                                             (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                                                    {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :thunder-clap/fx-update
        (level-effects/enqueue-level-effect! thunder-clap-effect-id
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :update
                  :ticks (long (or (:ticks payload) 0))
                  :charge-ratio (double (or (:charge-ratio payload) 0.0))
                  :target (get payload :target)})
          {:ctx-id ctx-id :channel channel})
        :thunder-clap/fx-perform
        (level-effects/enqueue-level-effect! thunder-clap-effect-id
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :perform
                  :performed? (boolean (:performed? payload))
                  :ticks (long (or (:ticks payload) (:charge-ticks payload) 0))
                  :charge-ratio (double (or (:charge-ratio payload) 0.0))
                  :target (get payload :target)})
          {:ctx-id ctx-id :channel channel})
        :thunder-clap/fx-end
        (level-effects/enqueue-level-effect! thunder-clap-effect-id
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :end
                  :performed? (boolean (:performed? payload))
                  :ticks (long (or (:ticks payload) (:charge-ticks payload) 0))
                  :charge-ratio (double (or (:charge-ratio payload) 0.0))
                  :target (get payload :target)})
          {:ctx-id ctx-id :channel channel}))))
  nil)
