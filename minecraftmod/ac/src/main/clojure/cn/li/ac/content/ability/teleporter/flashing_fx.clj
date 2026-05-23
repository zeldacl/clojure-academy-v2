(ns cn.li.ac.content.ability.teleporter.flashing-fx
  "Client FX for Flashing: movement preview + perform burst + cleanup."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue!
  [{:keys [mode] :as payload}]
  (case mode
    :state-start
    (swap! fx-state (fn [_] {:preview nil :burst []}))

    :preview-start
    (swap! fx-state update (if @fx-state :preview :preview)
           (constantly {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}))

    :preview-update
    (swap! fx-state update (if @fx-state :preview :preview)
           (constantly {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}))

    :preview-end
    (swap! fx-state assoc :preview nil)

    :perform
    (do
      (swap! fx-state update :burst (fnil conj [])
             {:ttl 8
              :from {:x (:from-x payload) :y (:from-y payload) :z (:from-z payload)}
              :to {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}})
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.tp_flashing" :volume 0.45 :pitch (+ 0.95 (rand 0.2))}))

    :state-end
    (reset! fx-state nil)

    nil))

(defn- tick!
  []
  (when-let [state @fx-state]
    (when-let [{:keys [x y z]} (:preview state)]
      (client-particles/queue-particle-effect!
        {:type :particle :particle-type :portal
         :x (double x) :y (double y) :z (double z)
         :count 2 :speed 0.02
         :offset-x 0.2 :offset-y 0.4 :offset-z 0.2}))
    (let [burst' (->> (or (:burst state) [])
                      (map (fn [b] (update b :ttl dec)))
                      (filter (fn [b] (pos? (long (:ttl b)))))
                      vec)]
      (doseq [b burst']
        (let [{fx :x fy :y fz :z} (:from b)
              {tx :x ty :y tz :z} (:to b)]
          (client-particles/queue-particle-effect!
            {:type :particle :particle-type :portal
             :x (double fx) :y (double fy) :z (double fz)
             :count 4 :speed 0.05
             :offset-x 0.35 :offset-y 0.5 :offset-z 0.35})
          (client-particles/queue-particle-effect!
            {:type :particle :particle-type :portal
             :x (double tx) :y (double ty) :z (double tz)
             :count 4 :speed 0.05
             :offset-x 0.35 :offset-y 0.5 :offset-z 0.35})))
      (swap! fx-state assoc :burst burst'))))

(defn- build-plan [_cp _hcp _tick]
  nil)

(defn init!
  []
  (level-effects/register-level-effect! :flashing
    {:enqueue-fn enqueue!
     :tick-fn tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:flashing/fx-state-start
     :flashing/fx-preview-start
     :flashing/fx-preview-update
     :flashing/fx-preview-end
     :flashing/fx-perform
     :flashing/fx-state-end]
    (fn [_ctx-id channel payload]
      (case channel
        :flashing/fx-state-start
        (level-effects/enqueue-level-effect! :flashing {:mode :state-start})

        :flashing/fx-preview-start
        (level-effects/enqueue-level-effect! :flashing
          {:mode :preview-start
           :to-x (:to-x payload) :to-y (:to-y payload) :to-z (:to-z payload)})

        :flashing/fx-preview-update
        (level-effects/enqueue-level-effect! :flashing
          {:mode :preview-update
           :to-x (:to-x payload) :to-y (:to-y payload) :to-z (:to-z payload)})

        :flashing/fx-preview-end
        (level-effects/enqueue-level-effect! :flashing {:mode :preview-end})

        :flashing/fx-perform
        (level-effects/enqueue-level-effect! :flashing
          {:mode :perform
           :from-x (:from-x payload) :from-y (:from-y payload) :from-z (:from-z payload)
           :to-x (:to-x payload) :to-y (:to-y payload) :to-z (:to-z payload)})

        :flashing/fx-state-end
        (level-effects/enqueue-level-effect! :flashing {:mode :state-end})

        nil)))
  nil)
