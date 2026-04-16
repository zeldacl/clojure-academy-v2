(ns cn.li.ac.content.ability.electromaster.railgun
  "Railgun skill port aligned to the original Electromaster behavior.

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.util.log :as log]))

(def ^:private accepted-item-ids
  #{"minecraft:iron_ingot" "minecraft:iron_block"})

(def ^:private coin-window-ms 1000)
(def ^:private coin-active-threshold 0.6)
(def ^:private coin-perform-threshold 0.7)
(def ^:private item-charge-ticks 20)
(def ^:private beam-radius 2.0)
(def ^:private beam-query-radius 30.0)
(def ^:private beam-step 0.9)
(def ^:private beam-max-distance 50.0)
(def ^:private beam-visual-distance 45.0)
(def ^:private reflect-distance 15.0)
(def ^:private reflect-damage 14.0)

(defn- lerp
  [a b t]
  (+ a (* (- b a) (double t))))

(defn- v+
  [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))} )

(defn- v-
  [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn- v*
  [a scale]
  {:x (* (double (:x a)) (double scale))
   :y (* (double (:y a)) (double scale))
   :z (* (double (:z a)) (double scale))})

(defn- dot
  [a b]
  (+ (* (:x a) (:x b))
     (* (:y a) (:y b))
     (* (:z a) (:z b))))

(defn- vlen
  [a]
  (Math/sqrt (dot a a)))

(defn- normalize
  [a]
  (let [len (max 1.0e-6 (vlen a))]
    (v* a (/ 1.0 len))))

(defn- cross
  [a b]
  {:x (- (* (:y a) (:z b)) (* (:z a) (:y b)))
   :y (- (* (:z a) (:x b)) (* (:x a) (:z b)))
   :z (- (* (:x a) (:y b)) (* (:y a) (:x b)))})

(defn- distance-3d
  [a b]
  (vlen (v- a b)))

(defn- distance-sq-3d
  [a b]
  (let [dx (- (double (:x a)) (double (:x b)))
        dy (- (double (:y a)) (double (:y b)))
        dz (- (double (:z a)) (double (:z b)))]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn- floor-int [value]
  (int (Math/floor (double value))))

(defn- eye-pos
  [{:keys [x y z]}]
  {:x (double x)
   :y (+ (double y) 1.62)
   :z (double z)})

(defn- orthonormal-basis
  [dir]
  (let [up-axis (if (> (Math/abs (double (:y dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (normalize (cross dir up-axis))
        up (normalize (cross right dir))]
    [right up]))

(defn register-coin-throw!
  "Register a railgun coin throw window for a player.
  Called from platform item-action hook when coin is used in ability mode."
  [player-id payload]
  (let [now-ms (long (or (:timestamp-ms payload) (System/currentTimeMillis)))]
    (ps/update-player-state!
      player-id
      assoc-in
      [:runtime :railgun :coin-window]
      {:start-ms now-ms
       :window-ms coin-window-ms
       :source :coin-item})
    (ps/mark-dirty! player-id)
    true))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :railgun :exp] 0.0)))

(defn- player-state-pos
  [player-id]
  (get (ps/get-player-state player-id) :position {:world-id "minecraft:overworld"
                                                  :x 0.0 :y 64.0 :z 0.0}))

(defn- player-world-id
  [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- current-main-hand-item-id
  [player]
  (when player
    (entity/player-get-main-hand-item-id player)))

(defn- accepted-item-in-hand?
  [player]
  (contains? accepted-item-ids (current-main-hand-item-id player)))

(defn- consume-item-for-shot!
  [player]
  (if (or (nil? player) (entity/player-creative? player))
    true
    (entity/player-consume-main-hand-item! player 1)))

(defn- apply-railgun-cooldown!
  [player-id exp]
  (let [cd-ticks (int (Math/round (double (lerp 300.0 160.0 exp))))]
    (ps/update-cooldown-data! player-id cd/set-main-cooldown :railgun (max 1 cd-ticks))))

(defn- add-railgun-exp!
  [player-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :railgun
                                  amount
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- clear-coin-window!
  [player-id]
  (ps/update-player-state! player-id update-in [:runtime :railgun] dissoc :coin-window)
  (ps/mark-dirty! player-id))

(defn- coin-progress
  [coin-window now-ms]
  (let [elapsed (- (long now-ms) (long (:start-ms coin-window)))
        window (max 1 (long (:window-ms coin-window)))]
    (double (/ (max 0 elapsed) window))))

(defn- consume-coin-qte-window!
  [player-id now-ms]
  (let [coin-window (get-in (ps/get-player-state player-id) [:runtime :railgun :coin-window])]
    (if-not coin-window
      {:has-window? false :active? false :perform? false :progress 0.0}
      (let [p (coin-progress coin-window now-ms)]
        (clear-coin-window! player-id)
        {:has-window? true
         :progress p
         :active? (>= p coin-active-threshold)
         :perform? (>= p coin-perform-threshold)}))))

(defn- peek-coin-qte-window
  [player-id now-ms]
  (let [coin-window (get-in (ps/get-player-state player-id) [:runtime :railgun :coin-window])]
    (if-not coin-window
      {:has-window? false :active? false :perform? false :progress 0.0}
      (let [p (coin-progress coin-window now-ms)]
        {:has-window? true
         :progress p
         :active? (>= p coin-active-threshold)
         :perform? (>= p coin-perform-threshold)}))))

;; ---------------------------------------------------------------------------
;; Cost DSL hooks
;; ---------------------------------------------------------------------------

(defn railgun-cost-creative?
  [{:keys [player]}]
  (boolean (and player (entity/player-creative? player))))

(defn railgun-cost-down-cp
  [{:keys [player-id]}]
  (let [qte (peek-coin-qte-window player-id (System/currentTimeMillis))]
    (if (:perform? qte)
      (lerp 200.0 450.0 (get-skill-exp player-id))
      0.0)))

(defn railgun-cost-down-overload
  [{:keys [player-id]}]
  (let [qte (peek-coin-qte-window player-id (System/currentTimeMillis))]
    (if (:perform? qte)
      (lerp 180.0 120.0 (get-skill-exp player-id))
      0.0)))

(defn- item-charge-ready?
  [ctx-id player]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx-data)
          mode (:mode skill-state)
          ticks-left (max 0 (int (or (:charge-ticks skill-state) 0)))]
      (and (= mode :item-charge)
           (<= ticks-left 1)
           (accepted-item-in-hand? player)))))

(defn railgun-cost-tick-cp
  [{:keys [player-id ctx-id player]}]
  (if (item-charge-ready? ctx-id player)
    (lerp 200.0 450.0 (get-skill-exp player-id))
    0.0))

(defn railgun-cost-tick-overload
  [{:keys [player-id ctx-id player]}]
  (if (item-charge-ready? ctx-id player)
    (lerp 180.0 120.0 (get-skill-exp player-id))
    0.0))

(defn- railgun-candidates
  [origin-player-id world-id start-pos dir]
  (if-not world-effects/*world-effects*
    []
    (let [mid-pos (v+ start-pos (v* dir (/ beam-max-distance 2.0)))]
      (->> (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                  world-id
                                                  (:x mid-pos) (:y mid-pos) (:z mid-pos)
                                                  beam-query-radius)
           (remove (fn [{:keys [uuid]}]
                     (= uuid origin-player-id)))
             (map (fn [{:keys [x y z] :as target}]
                  (let [to-target (v- {:x x :y y :z z} start-pos)
                        forward-dist (dot to-target dir)
                        closest-point (v+ start-pos (v* dir forward-dist))
                        radial-dist (distance-3d {:x x :y y :z z} closest-point)]
                    (assoc target :forward-dist forward-dist :radial-dist radial-dist))))
           (filter (fn [{:keys [forward-dist radial-dist]}]
                     (and (<= 0.0 forward-dist beam-max-distance)
                          (<= radial-dist (* beam-radius 1.2)))))
           vec))))

(defn- compute-entity-damage
  [start-damage radial-distance]
  (let [dist (min beam-max-distance (max 0.0 (double radial-distance)))
        factor (lerp 1.0 0.2 (/ dist beam-max-distance))]
    (* (double start-damage) factor)))

(defn- toggle-active?
  [player-id skill-id]
  (some (fn [[_ ctx-data]]
          (and (= (:player-id ctx-data) player-id)
               (toggle/is-toggle-active? ctx-data skill-id)))
        (ctx/get-all-contexts)))

(defn- vec-reflection-can-reflect?
  [target-player-id incoming-damage]
  (when (toggle-active? target-player-id :vec-reflection)
    (when-let [state (ps/get-player-state target-player-id)]
      (let [exp (get-in state [:ability-data :skills :vec-reflection :exp] 0.0)
            consumption (* incoming-damage (lerp 20.0 15.0 exp))
            current-cp (get-in state [:resource-data :cur-cp] 0.0)]
        (>= (double current-cp) (double consumption))))))

(defn- break-beam-blocks!
  [player-id world-id start-pos dir max-distance energy]
  (when (and block-manip/*block-manipulation* (pos? energy) (pos? max-distance))
    (let [[right up] (orthonormal-basis dir)
          processed-starts (atom #{})
          sample-points (transient [])]
      (doseq [s (range (- beam-radius) (+ beam-radius beam-step) beam-step)
              t (range (- beam-radius) (+ beam-radius beam-step) beam-step)]
        (when (<= (+ (* s s) (* t t)) (* beam-radius beam-radius))
          (let [offset (v+ (v* right s) (v* up t))
                origin (v+ start-pos offset)
                key [(floor-int (:x origin)) (floor-int (:y origin)) (floor-int (:z origin))]]
            (when-not (contains? @processed-starts key)
              (swap! processed-starts conj key)
              (conj! sample-points origin)))))
      (let [origins (persistent! sample-points)
            line-energy (if (seq origins)
                          (/ (double energy) (double (count origins)))
                          0.0)
            neighbor-offsets [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]]]
        (doseq [origin origins]
          (loop [travel 0.0
                 remaining-energy (* line-energy (+ 0.95 (* 0.1 (rand))))]
            (when (and (<= travel max-distance) (pos? remaining-energy))
              (let [pos (v+ origin (v* dir travel))
                    bx (floor-int (:x pos))
                    by (floor-int (:y pos))
                    bz (floor-int (:z pos))
                    hardness (block-manip/get-block-hardness block-manip/*block-manipulation*
                                                             world-id bx by bz)]
                (if (or (nil? hardness) (neg? (double hardness)))
                  (recur (+ travel 1.0) remaining-energy)
                  (if (and (pos? (double hardness))
                           (<= (double hardness) remaining-energy)
                           (block-manip/can-break-block? block-manip/*block-manipulation*
                                                         player-id world-id bx by bz))
                    (do
                      (block-manip/break-block! block-manip/*block-manipulation*
                                                player-id world-id bx by bz (< (rand) 0.05))
                      (when (< (rand) 0.05)
                        (let [[ox oy oz] (rand-nth neighbor-offsets)
                              nx (+ bx ox)
                              ny (+ by oy)
                              nz (+ bz oz)]
                          (when (block-manip/can-break-block? block-manip/*block-manipulation*
                                                              player-id world-id nx ny nz)
                            (block-manip/break-block! block-manip/*block-manipulation*
                                                      player-id world-id nx ny nz false))))
                      (recur (+ travel 1.0) (- remaining-energy (double hardness))))
                    (recur (+ travel 1.0) 0.0)))))))))))

(defn- perform-reflection-shot!
  [ctx-id reflector-player-id]
  (let [world-id (player-world-id reflector-player-id)
        origin-pos (player-state-pos reflector-player-id)
        look-vec (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* reflector-player-id))]
    (when look-vec
      (let [start-pos (eye-pos origin-pos)
            hit (raycast/raycast-entities raycast/*raycast*
                                          world-id
                                          (:x start-pos) (:y start-pos) (:z start-pos)
                                          (:dx look-vec) (:dy look-vec) (:dz look-vec)
                                          reflect-distance)
            actual-distance (if (= (:hit-type hit) :entity)
                              (double (or (:distance hit) reflect-distance))
                              reflect-distance)
            end-pos (v+ start-pos (v* {:x (:dx look-vec) :y (:dy look-vec) :z (:dz look-vec)} actual-distance))]
        (ctx/ctx-send-to-client! ctx-id :railgun/fx-reflect
                                 {:mode :reflect
                                  :start start-pos
                                  :end end-pos
                                  :hit-distance actual-distance})
        (when (and (= (:hit-type hit) :entity) entity-damage/*entity-damage*)
          (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                             world-id
                                             (:uuid hit)
                                             reflect-damage
                                             :magic)
          true)))))

(defn- perform-main-shot!
  [player-id ctx-id exp]
  (let [world-id (player-world-id player-id)
        origin-pos (player-state-pos player-id)
        visual-start-pos (eye-pos origin-pos)
        damage-start-pos {:x (double (:x origin-pos))
                          :y (double (:y origin-pos))
                          :z (double (:z origin-pos))}
        look-vec (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (if-not look-vec
      {:performed? false}
      (let [dir (normalize {:x (:dx look-vec) :y (:dy look-vec) :z (:dz look-vec)})
            damage (lerp 60.0 110.0 exp)
            block-energy (lerp 900.0 2000.0 exp)
            candidates (->> (railgun-candidates player-id world-id damage-start-pos dir)
                            (sort-by (fn [{:keys [x y z]}]
                                       (distance-sq-3d origin-pos {:x x :y y :z z})))
                            vec)
            step-result (fn [{:keys [stop? reflection-distance reflection-hit? normal-hit-count] :as acc}
                             {:keys [uuid x y z radial-dist]}]
                          (if stop?
                            (reduced acc)
                            (if (and (ps/get-player-state uuid)
                                     (vec-reflection-can-reflect? uuid damage))
                              (let [distance-to-reflector (distance-3d origin-pos {:x x :y y :z z})
                                    reflected-hit? (boolean (perform-reflection-shot! ctx-id uuid))]
                                (reduced {:stop? true
                                          :reflection-distance distance-to-reflector
                                          :reflection-hit? reflected-hit?
                                          :normal-hit-count normal-hit-count}))
                              (do
                                (when entity-damage/*entity-damage*
                                  (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                                     world-id
                                                                     uuid
                                                                     (compute-entity-damage damage radial-dist)
                                                                     :magic))
                                {:stop? false
                                 :reflection-distance reflection-distance
                                 :reflection-hit? reflection-hit?
                                 :normal-hit-count (inc normal-hit-count)}))))
            result (reduce step-result
                           {:stop? false
                            :reflection-distance nil
                            :reflection-hit? false
                            :normal-hit-count 0}
                           candidates)
            block-distance (min beam-max-distance (double (or (:reflection-distance result) beam-max-distance)))
            visual-distance (min beam-visual-distance (double (or (:reflection-distance result) beam-visual-distance)))
            end-pos (v+ visual-start-pos (v* dir visual-distance))]
          (break-beam-blocks! player-id world-id damage-start-pos dir block-distance block-energy)
        (ctx/ctx-send-to-client! ctx-id :railgun/fx-shot
                                 {:mode :perform
                        :start visual-start-pos
                                  :end end-pos
                                  :hit-distance visual-distance})
        {:performed? true
         :reflection-hit? (:reflection-hit? result)
         :normal-hit-count (:normal-hit-count result)
         :visual-distance visual-distance}))))

(defn railgun-on-key-down
  "Railgun activation logic.
  1) Coin QTE perform when coin window progress > 0.7
  2) Otherwise start 20-tick iron-item charge"
  [{:keys [player-id ctx-id player cost-ok?]}]
  (try
    (let [exp (get-skill-exp player-id)
          now-ms (System/currentTimeMillis)
          qte (consume-coin-qte-window! player-id now-ms)]
      (cond
        (:perform? qte)
        (if cost-ok?
          (let [{:keys [performed? reflection-hit? normal-hit-count]} (perform-main-shot! player-id ctx-id exp)]
            (when performed?
              (add-railgun-exp! player-id (if reflection-hit? 0.01 0.005))
              (apply-railgun-cooldown! player-id exp)
              (ctx/update-context! ctx-id assoc :skill-state
                                   {:fired true
                                    :mode :performed
                                    :hit-count normal-hit-count}))
            (log/debug "Railgun coin-QTE perform" player-id))
          (ctx/update-context! ctx-id assoc :skill-state {:fired false :mode :coin-qte-no-resource}))

        (:has-window? qte)
        (do
          (ctx/update-context! ctx-id assoc :skill-state {:fired false :mode :coin-qte-miss})
          (log/debug "Railgun coin-QTE miss" player-id (:progress qte)))

        (accepted-item-in-hand? player)
        (ctx/update-context! ctx-id assoc :skill-state
                             {:fired false
                              :mode :item-charge
                              :charge-ticks item-charge-ticks
                              :hit-count 0})

        :else
        (ctx/update-context! ctx-id assoc :skill-state {:fired false :mode :idle-no-trigger})))
    (catch Exception e
      (log/warn "Railgun key-down failed:" (ex-message e)))))

(defn railgun-on-key-tick
  "Item-charge path: countdown to automatic perform at 20 ticks."
  [{:keys [player-id ctx-id player cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            mode (:mode skill-state)]
        (when (= mode :item-charge)
          (let [ticks-left (max 0 (int (or (:charge-ticks skill-state) 0)))]
            (if (<= ticks-left 1)
              (do
                (if (and (accepted-item-in-hand? player)
                         (consume-item-for-shot! player)
                         cost-ok?)
                  (let [{:keys [performed? reflection-hit? normal-hit-count]} (perform-main-shot! player-id ctx-id (get-skill-exp player-id))]
                    (when performed?
                      (add-railgun-exp! player-id (if reflection-hit? 0.01 0.005))
                      (apply-railgun-cooldown! player-id (get-skill-exp player-id))
                      (ctx/update-context! ctx-id assoc :skill-state
                                           {:fired true
                                            :mode :performed
                                            :hit-count normal-hit-count})))
                  (ctx/update-context! ctx-id assoc :skill-state
                                       (assoc skill-state :fired false :mode :item-charge-failed)))
                (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] 0))
              (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] (dec ticks-left)))))))
    (catch Exception e
      (log/warn "Railgun key-tick failed:" (ex-message e)))))

(defn railgun-on-key-up
  "Release cancels unfinished item charge. Cooldown is applied only on successful perform."
  [{:keys [ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            mode (:mode skill-state)
            fired (boolean (:fired skill-state))]
        (when (and (= mode :item-charge) (not fired))
          (ctx/update-context! ctx-id assoc :skill-state
                               (assoc skill-state :mode :item-charge-cancelled :charge-ticks 0)))
        (when fired
          (log/debug "Railgun completed"))))
    (catch Exception e
      (log/warn "Railgun key-up failed:" (ex-message e)))))

(defn railgun-on-key-abort
  "Clean up railgun state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "Railgun aborted")
    (catch Exception e
      (log/warn "Railgun key-abort failed:" (ex-message e)))))
