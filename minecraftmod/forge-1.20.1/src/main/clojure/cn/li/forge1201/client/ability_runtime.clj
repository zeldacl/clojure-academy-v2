(ns cn.li.forge1201.client.ability-runtime
  "CLIENT-ONLY ability input runtime (minimal runnable loop)."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.forge1201.ability.network :as ability-net]
            [cn.li.forge1201.client.ability-client-state :as client-state]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.client Minecraft]
           [net.minecraft.core.registries BuiltInRegistries]
           [cn.li.forge1201.client.effect IntensifyEffectSpawner]))

(defonce ^:private local-contexts (atom {}))
(defonce ^:private sync-cache (atom {}))
(defonce ^:private slot-active-contexts (atom {}))
(defonce ^:private railgun-local-state (atom {}))
(defonce ^:private body-intensify-local-state (atom {}))
(defonce ^:private current-charging-local-state (atom {}))
(defonce ^:private mag-manip-local-state (atom {}))
(defonce ^:private client-tick-counter (atom 0))
(defonce ^:private tick-listener-registered? (atom false))

(def ^:private keepalive-interval-ticks 10)
(def ^:private railgun-coin-window-ms 1000)
(def ^:private railgun-item-charge-ticks 20)
(def ^:private railgun-accepted-items #{"minecraft:iron_ingot" "minecraft:iron_block"})
(def ^:private body-intensify-max-time 40)
(def ^:private body-intensify-tolerant-time 100)
(def ^:private current-charging-loop-interval 8)

(defn active-contexts [] @local-contexts)

(defn latest-sync
  [player-uuid]
  (get @sync-cache player-uuid))

(defn- player-runtime-state
  [player-uuid]
  (or (ability-runtime/get-player-state player-uuid)
      (latest-sync player-uuid)))

(defn- resolve-slot-skill-id
  [player-uuid key-idx]
  (when-let [state (player-runtime-state player-uuid)]
    (let [preset (:preset-data state)
          active (or (:active-preset preset) 0)
          binding (get-in preset [:slots [active key-idx]])]
      (when (and (vector? binding) (= 2 (count binding)))
        (ability-runtime/client-get-skill-by-controllable (first binding) (second binding))))))

(defn- slot-key
  [player-uuid key-idx]
  [player-uuid (int key-idx)])

(defn- now-ms [] (System/currentTimeMillis))

(defn- queue-particle!
  [particle-cmd]
  (when-let [queue-fn (resolve 'cn.li.ac.ability.client.effects.particles/queue-particle-effect!)]
    (@queue-fn particle-cmd)))

(defn- queue-sound!
  [sound-cmd]
  (when-let [queue-fn (resolve 'cn.li.ac.ability.client.effects.sounds/queue-sound-effect!)]
    (@queue-fn sound-cmd)))

(declare local-player-pos)

(defn- line-points
  [start end segments]
  (let [n (max 1 (int segments))
        sx (double (:x start))
        sy (double (:y start))
        sz (double (:z start))
        ex (double (:x end))
        ey (double (:y end))
        ez (double (:z end))]
    (map (fn [i]
           (let [t (/ (double i) n)]
             {:x (+ sx (* (- ex sx) t))
              :y (+ sy (* (- ey sy) t))
              :z (+ sz (* (- ez sz) t))}))
         (range (inc n)))))

(defn- play-railgun-shot-fx!
  [{:keys [mode start end hit-distance]}]
  (when-let [enqueue-fn (resolve 'cn.li.forge1201.client.railgun-render/enqueue-shot-fx!)]
    (@enqueue-fn {:mode mode :start start :end end :hit-distance hit-distance}))
  (let [distance (max 1.0 (double (or hit-distance 1.0)))
        segments (int (Math/max 8.0 (Math/min 48.0 (* 1.2 distance))))]
    (doseq [{:keys [x y z]} (line-points start end segments)]
      (queue-particle! {:type :particle
                        :particle-type :electric-spark
                        :x x :y y :z z
                        :count 2
                        :speed 0.02
                        :offset-x 0.08
                        :offset-y 0.08
                        :offset-z 0.08}))
    (when-let [{:keys [x y z]} start]
      (queue-particle! {:type :particle
                        :particle-type :electric-spark
                        :x x :y y :z z
                        :count 12
                        :speed 0.09
                        :offset-x 0.2
                        :offset-y 0.2
                        :offset-z 0.2}))
    (when (= mode :perform)
      (queue-sound! {:type :sound
                     :sound-id "my_mod:em.railgun"
                     :volume 0.5
                     :pitch 1.0}))))

(defn- play-mag-manip-hold-fx!
  [{:keys [mode focus block-id]}]
  (when focus
    (when (= mode :hold-start)
      (reset! mag-manip-local-state {:active? true :last-loop-ms 0 :block-id block-id}))
    (queue-particle! {:type :particle
                      :particle-type :electric-spark
                      :x (:x focus)
                      :y (:y focus)
                      :z (:z focus)
                  :count (if (= mode :hold-start) 12 4)
                      :speed 0.05
                  :offset-x 0.14
                  :offset-y 0.14
                  :offset-z 0.14})
    (queue-particle! {:type :particle
                  :particle-type :electric-spark
                  :x (:x focus)
                  :y (:y focus)
                  :z (:z focus)
                  :count (if (= mode :hold-start) 4 2)
                  :speed 0.02
                  :offset-x 0.36
                  :offset-y 0.24
                  :offset-z 0.36})
    (when (= mode :hold-start)
      (queue-sound! {:type :sound
                 :sound-id "my_mod:em.lf_loop"
                 :volume 0.20
                 :pitch 1.0}))
    (when (= mode :hold-loop)
      (let [now (now-ms)
            last-ms (long (or (:last-loop-ms @mag-manip-local-state) 0))]
        (when (>= (- now last-ms) 420)
          (queue-sound! {:type :sound
                     :sound-id "my_mod:em.lf_loop"
                     :volume 0.14
                     :pitch 1.0})
          (swap! mag-manip-local-state assoc :last-loop-ms now))))))

(defn- play-mag-manip-throw-fx!
  [{:keys [start end]}]
  (when (and start end)
    (reset! mag-manip-local-state {:active? false :last-loop-ms 0})
    (let [distance (max 1.0 (Math/sqrt (+ (Math/pow (- (:x end) (:x start)) 2.0)
                                          (Math/pow (- (:y end) (:y start)) 2.0)
                                          (Math/pow (- (:z end) (:z start)) 2.0))))
          segments (int (Math/max 8.0 (Math/min 32.0 (* 1.4 distance))))]
      (doseq [{:keys [x y z]} (line-points start end segments)]
        (queue-particle! {:type :particle
                          :particle-type :electric-spark
                          :x x :y y :z z
                          :count 2
                          :speed 0.03
                          :offset-x 0.06
                          :offset-y 0.06
                          :offset-z 0.06}))
      (queue-sound! {:type :sound
                 :sound-id "my_mod:em.mag_manip"
                 :volume 0.45
                 :pitch 1.0}))))

(defn- start-body-intensify-fx!
  [ctx-id]
  (swap! body-intensify-local-state assoc
         ctx-id
         {:charging? true
          :charge-ticks 0
          :last-loop-sound-tick 0})
  (when-let [{:keys [x y z]} (local-player-pos)]
    (queue-particle! {:type :particle
                      :particle-type :electric-spark
                      :x x :y (+ y 1.2) :z z
                      :count 8
                      :speed 0.06
                      :offset-x 0.22
                      :offset-y 0.14
                      :offset-z 0.22})))

(defn- finish-body-intensify-fx!
  [ctx-id performed?]
  (let [{:keys [charge-ticks]} (get @body-intensify-local-state ctx-id)
        ticks (long (or charge-ticks 0))]
    (when-let [{:keys [x y z]} (local-player-pos)]
      (if performed?
        (do
          (IntensifyEffectSpawner/spawnLocal)
          (queue-sound! {:type :sound
                         :sound-id "my_mod:em.intensify_activate"
                         :volume 0.45
                         :pitch 1.0})
          (queue-particle! {:type :particle
                            :particle-type :electric-spark
                            :x x :y (+ y 1.25) :z z
                            :count (int (min 40 (+ 12 (* 0.6 ticks))))
                            :speed 0.08
                            :offset-x 0.4
                            :offset-y 0.3
                            :offset-z 0.4}))
        (queue-particle! {:type :particle
                          :particle-type :electric-spark
                          :x x :y (+ y 1.25) :z z
                          :count 4
                          :speed 0.03
                          :offset-x 0.12
                          :offset-y 0.1
                          :offset-z 0.12})))
    (swap! body-intensify-local-state dissoc ctx-id)))

(defn- local-player-eye-pos
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      {:x (.getX player)
       :y (+ (.getY player) 1.62)
       :z (.getZ player)})))

(defn- local-player-look-end
  [distance]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (let [look (.getLookAngle player)
            eye (local-player-eye-pos)]
        {:x (+ (:x eye) (* (.x look) distance))
         :y (+ (:y eye) (* (.y look) distance))
         :z (+ (:z eye) (* (.z look) distance))}))))

(defn- start-current-charging-fx!
  [ctx-id is-item]
  (swap! current-charging-local-state assoc
         ctx-id
         {:active? true
          :is-item (boolean is-item)
          :good? false
          :charge-ticks 0
          :target nil
          :block-pos nil
          :last-loop-sound-tick 0
          :blend-out-until-ms 0}))

(defn- update-current-charging-fx!
  [ctx-id payload]
  (swap! current-charging-local-state update
         ctx-id
         (fn [st]
           (let [base (or st {:active? true
                              :is-item (boolean (:is-item payload))
                              :last-loop-sound-tick 0
                              :blend-out-until-ms 0})]
             (assoc base
                    :active? true
                    :is-item (boolean (:is-item payload))
                    :good? (boolean (:good? payload))
                    :charge-ticks (long (or (:charge-ticks payload) (:charge-ticks base) 0))
                    :target (:target payload)
                    :block-pos (:block-pos payload))))))

(defn- finish-current-charging-fx!
  [ctx-id]
  (let [until (+ (now-ms) 1000)]
    (swap! current-charging-local-state update ctx-id
           (fn [st]
             (when st
               (assoc st :active? false :blend-out-until-ms until))))))

(defn- body-intensify-loop-envelope
  [charge-ticks]
  (let [ticks (max 0 (long charge-ticks))
        active-ratio (min 1.0 (/ (double ticks) (double body-intensify-max-time)))
        tolerant-ratio (if (<= ticks body-intensify-max-time)
                         0.0
                         (min 1.0 (/ (double (- ticks body-intensify-max-time))
                                     (double (max 1 (- body-intensify-tolerant-time body-intensify-max-time))))))
        fade-scale (- 1.0 (* 0.55 tolerant-ratio))
        volume (* (+ 0.1 (* 0.24 active-ratio)) fade-scale)
        pitch (+ 0.88 (* 0.2 active-ratio) (* -0.08 tolerant-ratio))
        base-interval (Math/round (double (+ 14.0 (* -8.0 active-ratio))))
        interval (+ (int base-interval)
                    (int (Math/round (double (* 8.0 tolerant-ratio)))))]
    {:volume (float (max 0.08 volume))
     :pitch (float (max 0.8 pitch))
     :interval (max 4 interval)}))

(defn- register-context-fx-listeners!
  [ctx-id skill-id]
  (when-let [ctx-on-fn (resolve 'cn.li.ac.ability.context/ctx-on)]
    (when (= skill-id :railgun)
      (@ctx-on-fn ctx-id :railgun/fx-shot play-railgun-shot-fx!)
      (@ctx-on-fn ctx-id :railgun/fx-reflect play-railgun-shot-fx!))
    (when (= skill-id :mag-manip)
      (@ctx-on-fn ctx-id :mag-manip/fx-hold play-mag-manip-hold-fx!)
      (@ctx-on-fn ctx-id :mag-manip/fx-throw play-mag-manip-throw-fx!))
    (when (= skill-id :body-intensify)
      (@ctx-on-fn ctx-id :body-intensify/fx-end
                 (fn [{:keys [performed?]}]
                   (finish-body-intensify-fx! ctx-id (boolean performed?)))))
    (when (= skill-id :current-charging)
      (@ctx-on-fn ctx-id :current-charging/fx-start
                 (fn [{:keys [is-item]}]
                   (start-current-charging-fx! ctx-id (boolean is-item))))
      (@ctx-on-fn ctx-id :current-charging/fx-update
                 (fn [payload]
                   (update-current-charging-fx! ctx-id payload)))
      (@ctx-on-fn ctx-id :current-charging/fx-end
                 (fn [_]
                   (finish-current-charging-fx! ctx-id))))))

(defn- local-player-item-id
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (let [stack (.getMainHandItem player)]
        (when (and stack (not (.isEmpty stack)))
          (when-let [key (.getKey BuiltInRegistries/ITEM (.getItem stack))]
            (str (.getNamespace key) ":" (.getPath key))))))))

(defn- local-player-pos
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      {:x (.getX player) :y (.getY player) :z (.getZ player)})))

(defn- railgun-state
  [k]
  (get @railgun-local-state k))

(defn- set-railgun-state!
  [k v]
  (swap! railgun-local-state assoc k v))

(defn- clear-railgun-state!
  [k]
  (swap! railgun-local-state dissoc k))

(defn notify-railgun-coin-throw-client!
  [player-uuid]
  (let [k [player-uuid :coin]
        started-at (now-ms)
        expires-at (+ started-at railgun-coin-window-ms)]
    (set-railgun-state! k {:coin-started-at started-at
                           :coin-expires-at expires-at})
    (when-let [{:keys [x y z]} (local-player-pos)]
      (queue-particle! {:type :particle
                        :particle-type :electric-spark
                        :x x :y (+ y 1.4) :z z
                        :count 6
                        :speed 0.08
                        :offset-x 0.3
                        :offset-y 0.2
                        :offset-z 0.3}))))

(defn slot-visual-state
  [player-uuid key-idx]
  (let [k (slot-key player-uuid key-idx)
        ctx-id (get @slot-active-contexts k)
        charge? (pos? (long (or (get-in (railgun-state k) [:charge-ticks]) 0)))
        body-state (when ctx-id (get @body-intensify-local-state ctx-id))
        body-charge? (boolean (:charging? body-state))
        coin-state (railgun-state [player-uuid :coin])
        now (now-ms)
        coin-progress (let [start (long (or (:coin-started-at coin-state) now))
                            expires (long (or (:coin-expires-at coin-state) now))
                            window (max 1 (- expires start))]
                        (/ (double (max 0 (- now start))) (double window)))
        coin? (let [expires (long (or (:coin-expires-at coin-state) 0))]
                (> expires now))]
    (cond
      charge? :charge
      body-charge? :charge
      coin? (if (< coin-progress 0.6) :charge :active)
      ctx-id :active
      :else :idle)))

(defn railgun-charge-visual-state
  [player-uuid]
  (let [max-charge (reduce (fn [acc [[uuid key-idx] st]]
                             (if (and (= uuid player-uuid) (number? key-idx))
                               (max acc (long (or (:charge-ticks st) 0)))
                               acc))
                           0
                           @railgun-local-state)
        coin-expires (long (or (get-in @railgun-local-state [[player-uuid :coin] :coin-expires-at]) 0))
        coin-active? (> coin-expires (now-ms))]
    {:active? (or (pos? max-charge) coin-active?)
     :charge-ticks max-charge
     :coin-active? coin-active?
     :charge-ratio (double (/ (double max-charge) (double railgun-item-charge-ticks)))}))

(defn body-intensify-charge-visual-state
  []
  (let [charge (reduce (fn [acc [_ st]]
                         (max acc (long (or (:charge-ticks st) 0))))
                       0
                       @body-intensify-local-state)]
    {:active? (pos? charge)
     :charge-ticks charge
     :charge-ratio (min 1.0 (/ (double charge) (double body-intensify-max-time)))}))

(defn- abort-slot-contexts-for-player!
  [player-uuid]
  (doseq [[[uuid key-idx] ctx-id] @slot-active-contexts]
    (when (= uuid player-uuid)
      (when (get @local-contexts ctx-id)
        (ability-net/send-to-server! catalog/MSG-SKILL-KEY-ABORT {:ctx-id ctx-id})
        (ability-runtime/client-terminate-context! ctx-id nil)
        (swap! local-contexts dissoc ctx-id))
      (swap! slot-active-contexts dissoc [uuid key-idx]))))

(defn- refresh-local-context! [ctx-id]
  (if-let [ctx-map (ability-runtime/client-get-context ctx-id)]
    (swap! local-contexts assoc ctx-id ctx-map)
    (swap! local-contexts dissoc ctx-id)))

(defn- send-keepalive! [ctx-id]
  (ability-net/send-to-server! catalog/MSG-CTX-KEEPALIVE {:ctx-id ctx-id}))

(defn tick-client!
  []
  (let [tick (swap! client-tick-counter inc)]
    (when-let [tick-input-fn (resolve 'cn.li.forge1201.client.ability-input/tick-input!)]
      (@tick-input-fn))
    (when-let [tick-particles-fn (resolve 'cn.li.forge1201.client.effects.particle-bridge/tick-particles!)]
      (@tick-particles-fn))
    (when-let [tick-sounds-fn (resolve 'cn.li.forge1201.client.effects.sound-bridge/tick-sounds!)]
      (@tick-sounds-fn))
    (when (zero? (mod tick keepalive-interval-ticks))
      (doseq [ctx-id (keys @local-contexts)]
        (send-keepalive! ctx-id)))
    ;; Local railgun charge visuals (client prediction only).
    (doseq [[[_ key-idx :as k] st] @railgun-local-state]
      (cond
        (number? key-idx)
        (let [ticks-left (long (or (:charge-ticks st) 0))]
          (if (pos? ticks-left)
            (do
              (when (zero? (mod tick 2))
                (when-let [{:keys [x y z]} (local-player-pos)]
                  (queue-particle! {:type :particle
                                    :particle-type :electric-spark
                                    :x x :y (+ y 1.3) :z z
                                    :count 3
                                    :speed 0.05
                                    :offset-x 0.25
                                    :offset-y 0.12
                                    :offset-z 0.25})))
              (set-railgun-state! k (assoc st :charge-ticks (dec ticks-left))))
            (clear-railgun-state! k)))

        (= key-idx :coin)
        (let [expires (long (or (:coin-expires-at st) 0))]
          (when (<= expires (now-ms))
            (clear-railgun-state! k)))))
    ;; Local body-intensify charge visuals/sounds while context is active.
    (doseq [[ctx-id st] @body-intensify-local-state]
      (if-not (get @local-contexts ctx-id)
        (swap! body-intensify-local-state dissoc ctx-id)
        (let [next-charge (inc (long (or (:charge-ticks st) 0)))
              last-loop (long (or (:last-loop-sound-tick st) 0))
              {:keys [volume pitch interval]} (body-intensify-loop-envelope next-charge)]
          (swap! body-intensify-local-state assoc
                 ctx-id
                 (assoc st :charge-ticks next-charge))
          (when (zero? (mod tick 2))
            (when-let [{:keys [x y z]} (local-player-pos)]
              (queue-particle! {:type :particle
                                :particle-type :electric-spark
                                :x x :y (+ y 1.25) :z z
                                :count 2
                                :speed 0.03
                                :offset-x 0.15
                                :offset-y 0.12
                                :offset-z 0.15})))
          (when (>= (- tick last-loop) interval)
            (queue-sound! {:type :sound
                           :sound-id "my_mod:em.intensify_loop"
                           :volume volume
                           :pitch pitch})
            (swap! body-intensify-local-state update ctx-id assoc :last-loop-sound-tick tick)))))

    ;; CurrentCharging local visuals/sounds.
    (doseq [[ctx-id st] @current-charging-local-state]
      (let [active? (boolean (:active? st))
            expires (long (or (:blend-out-until-ms st) 0))]
        (if (and (not active?) (<= expires (now-ms)))
          (swap! current-charging-local-state dissoc ctx-id)
          (let [is-item (boolean (:is-item st))
                good? (boolean (:good? st))
                target (or (:target st) (local-player-look-end 15.0))
                eye (local-player-eye-pos)
                block-pos (:block-pos st)
                last-loop (long (or (:last-loop-sound-tick st) 0))]
            (when (and active? (>= (- tick last-loop) current-charging-loop-interval))
              (queue-sound! {:type :sound
                             :sound-id "my_mod:em.charge_loop"
                             :volume 0.3
                             :pitch 1.0})
              (swap! current-charging-local-state update ctx-id assoc :last-loop-sound-tick tick))

            (if is-item
              (when-let [{:keys [x y z]} (local-player-pos)]
                (queue-particle! {:type :particle
                                  :particle-type :electric-spark
                                  :x x :y (+ y 1.3) :z z
                                  :count (if active? 4 2)
                                  :speed 0.05
                                  :offset-x 0.22
                                  :offset-y 0.18
                                  :offset-z 0.22}))
              (do
                (when (and eye target)
                  (let [distance (max 1.0 (Math/sqrt (+ (Math/pow (- (:x target) (:x eye)) 2.0)
                                                        (Math/pow (- (:y target) (:y eye)) 2.0)
                                                        (Math/pow (- (:z target) (:z eye)) 2.0))))
                        segments (int (Math/max 6.0 (Math/min 18.0 (* 1.1 distance))))]
                    (doseq [{:keys [x y z]} (line-points eye target segments)]
                      (queue-particle! {:type :particle
                                        :particle-type :electric-spark
                                        :x x :y y :z z
                                        :count 1
                                        :speed 0.01
                                        :offset-x 0.02
                                        :offset-y 0.02
                                        :offset-z 0.02}))))
                (when (and good? block-pos)
                  (let [[bx by bz] block-pos]
                    (queue-particle! {:type :particle
                                      :particle-type :electric-spark
                                      :x (+ (double bx) 0.5)
                                      :y (+ (double by) 0.5)
                                      :z (+ (double bz) 0.5)
                                      :count (if active? 6 2)
                                      :speed 0.04
                                      :offset-x 0.25
                                      :offset-y 0.25
                                      :offset-z 0.25})))))))))))
(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (tick-client!)))

(defn- on-ctx-establish!
  [{:keys [ctx-id server-id]}]
  (ability-runtime/client-transition-to-alive! ctx-id server-id nil)
  (refresh-local-context! ctx-id)
  (when (= :body-intensify (:skill-id (get @local-contexts ctx-id)))
    (start-body-intensify-fx! ctx-id)))

(defn- on-ctx-terminate!
  [{:keys [ctx-id]}]
  (finish-body-intensify-fx! ctx-id false)
  (finish-current-charging-fx! ctx-id)
  (reset! mag-manip-local-state {})
  (ability-runtime/client-terminate-context! ctx-id nil)
  (swap! local-contexts dissoc ctx-id))

(defn- on-ctx-channel!
  [{:keys [ctx-id channel payload]}]
  (ability-runtime/client-send-context-local! ctx-id channel payload))

(defn- on-sync-ability!
  [{:keys [uuid ability-data]}]
  (when uuid
    (ability-runtime/client-update-ability-data! uuid ability-data)
    (swap! sync-cache update uuid (fnil assoc {}) :ability-data ability-data)))

(defn- on-sync-resource!
  [{:keys [uuid resource-data]}]
  (when uuid
    (ability-runtime/client-update-resource-data! uuid resource-data)
    (client-state/clear-client-activated!)
    (swap! sync-cache update uuid (fnil assoc {}) :resource-data resource-data)))

(defn- on-sync-cooldown!
  [{:keys [uuid cooldown-data]}]
  (when uuid
    (ability-runtime/client-update-cooldown-data! uuid cooldown-data)
    (swap! sync-cache update uuid (fnil assoc {}) :cooldown-data cooldown-data)))

(defn- on-sync-preset!
  [{:keys [uuid preset-data]}]
  (when uuid
    (let [old-active (get-in (latest-sync uuid) [:preset-data :active-preset])
          new-active (:active-preset preset-data)]
      (when (and (some? old-active)
                 (some? new-active)
                 (not= old-active new-active))
        (abort-slot-contexts-for-player! uuid)))
    (ability-runtime/client-update-preset-data! uuid preset-data)
    (swap! sync-cache update uuid (fnil assoc {}) :preset-data preset-data)))

(defn register-push-handlers!
  []
  (net-client/register-push-handler! catalog/MSG-CTX-ESTABLISH on-ctx-establish!)
  (net-client/register-push-handler! catalog/MSG-CTX-TERMINATE on-ctx-terminate!)
  (net-client/register-push-handler! catalog/MSG-CTX-CHANNEL on-ctx-channel!)
  (net-client/register-push-handler! catalog/MSG-SYNC-ABILITY on-sync-ability!)
  (net-client/register-push-handler! catalog/MSG-SYNC-RESOURCE on-sync-resource!)
  (net-client/register-push-handler! catalog/MSG-SYNC-COOLDOWN on-sync-cooldown!)
  (net-client/register-push-handler! catalog/MSG-SYNC-PRESET on-sync-preset!))

(defn on-key-down!
  [player-uuid skill-id]
  (let [ctx-map (ability-runtime/client-new-context player-uuid skill-id)]
    (ability-runtime/client-register-context! ctx-map)
    (register-context-fx-listeners! (:id ctx-map) skill-id)
    (swap! local-contexts assoc (:id ctx-map) ctx-map)
    (ability-net/send-to-server! catalog/MSG-CTX-BEGIN-LINK
                                 {:ctx-id (:id ctx-map) :skill-id skill-id})
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-DOWN
                                 {:ctx-id (:id ctx-map) :skill-id skill-id})
    ctx-map))

(defn on-key-tick!
  [ctx-id]
  (when-let [ctx-map (get @local-contexts ctx-id)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-TICK
                                 {:ctx-id ctx-id :skill-id (:skill-id ctx-map)})))

(defn on-key-up!
  [ctx-id]
  (when-let [ctx-map (get @local-contexts ctx-id)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-UP {:ctx-id ctx-id})
    ;; BodyIntensify/CurrentCharging need server feedback before local teardown.
    (when-not (#{:body-intensify :current-charging} (:skill-id ctx-map))
      (ability-runtime/client-terminate-context! ctx-id nil)
      (swap! local-contexts dissoc ctx-id))))

(defn on-slot-key-down!
  [player-uuid key-idx]
  (let [k (slot-key player-uuid key-idx)]
    (when-not (get @slot-active-contexts k)
      (when-let [skill-id (resolve-slot-skill-id player-uuid key-idx)]
        (when (= skill-id :railgun)
          (let [coin-state (railgun-state [player-uuid :coin])
                coin-active? (> (long (or (:coin-expires-at coin-state) 0)) (now-ms))
                item-id (local-player-item-id)]
            (when (and (not coin-active?) (contains? railgun-accepted-items item-id))
              (set-railgun-state! k {:charge-ticks railgun-item-charge-ticks}))))
        (let [ctx-map (on-key-down! player-uuid skill-id)]
          (swap! slot-active-contexts assoc k (:id ctx-map))
          ctx-map)))))

(defn on-slot-key-tick!
  [player-uuid key-idx]
  (when-let [ctx-id (get @slot-active-contexts (slot-key player-uuid key-idx))]
    (on-key-tick! ctx-id)))

(defn on-slot-key-up!
  [player-uuid key-idx]
  (let [k (slot-key player-uuid key-idx)]
    (when-let [ctx-id (get @slot-active-contexts k)]
      (on-key-up! ctx-id)
      (swap! slot-active-contexts dissoc k))
    (clear-railgun-state! k)))

(defn abort-all!
  []
  (doseq [ctx-id (keys @local-contexts)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-ABORT {:ctx-id ctx-id})
    (ability-runtime/client-terminate-context! ctx-id nil))
  (reset! slot-active-contexts {})
  (reset! railgun-local-state {})
  (reset! body-intensify-local-state {})
  (reset! current-charging-local-state {})
  (reset! local-contexts {}))

(defn current-charging-visual-state
  []
  (let [now (now-ms)
        states (vals @current-charging-local-state)
        active-states (filter :active? states)
        latest (last (sort-by :charge-ticks active-states))
        blend? (some (fn [st]
                       (and (not (:active? st))
                            (> (long (or (:blend-out-until-ms st) 0)) now)))
                     states)]
    {:active? (boolean (seq active-states))
     :blending? (boolean blend?)
     :is-item (boolean (:is-item latest))
     :good? (boolean (:good? latest))
     :charge-ticks (long (or (:charge-ticks latest) 0))
     :charge-ratio (min 1.0 (/ (double (long (or (:charge-ticks latest) 0))) 40.0))}))

(defn init! []
  (register-push-handlers!)
  (when (compare-and-set! tick-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-client-tick evt)))))
  (log/info "Ability client runtime initialized"))
