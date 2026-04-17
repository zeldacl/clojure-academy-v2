(ns cn.li.ac.content.ability.meltdowner.meltdowner
  "Meltdowner skill port aligned to original AcademyCraft Meltdowner behavior.

  Key alignment points:
  - Charge window: min 20, optimal cap 40, tolerant max 100 ticks
  - Start cost: overload lerp(200,170,exp), then keep overload floor
  - Tick cost: CP lerp(10,15,exp)
  - Perform: ranged beam with width lerp(2,3,exp), energy and damage scaled by charge rate
  - Reflection: stop main beam when VecReflection-like target reflects, then do reflected strike
  - Cooldown: timeRate(ct) * 20 * lerp(15,7,exp)
  - EXP gain on perform: timeRate(ct) * 0.002
  - Client FX: start/update/end + perform/reflection beam events

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.model.resource-data :as rdata]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ticks-min 20)
(def ^:private ticks-max 40)
(def ^:private ticks-tolerant 100)
(def ^:private max-increment 50.0)

(defn- lerp [a b t]
  (bal/lerp a b t))

(defn- clamp01 [x]
  (bal/clamp01 x))

(defn- v+ [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))})

(defn- v- [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn- v* [a scale]
  {:x (* (double (:x a)) (double scale))
   :y (* (double (:y a)) (double scale))
   :z (* (double (:z a)) (double scale))})

(defn- dot [a b]
  (+ (* (:x a) (:x b))
     (* (:y a) (:y b))
     (* (:z a) (:z b))))

(defn- vlen [a]
  (Math/sqrt (dot a a)))

(defn- normalize [a]
  (let [len (max 1.0e-6 (vlen a))]
    (v* a (/ 1.0 len))))

(defn- cross [a b]
  {:x (- (* (:y a) (:z b)) (* (:z a) (:y b)))
   :y (- (* (:z a) (:x b)) (* (:x a) (:z b)))
   :z (- (* (:x a) (:y b)) (* (:y a) (:x b)))})

(defn- distance-3d [a b]
  (vlen (v- a b)))

(defn- distance-sq-3d [a b]
  (let [dx (- (double (:x a)) (double (:x b)))
        dy (- (double (:y a)) (double (:y b)))
        dz (- (double (:z a)) (double (:z b)))]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn- floor-int [value]
  (int (Math/floor (double value))))

(defn- eye-pos [{:keys [x y z]}]
  {:x (double x)
   :y (+ (double y) 1.62)
   :z (double z)})

(defn- orthonormal-basis [dir]
  (let [up-axis (if (> (Math/abs (double (:y dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (normalize (cross dir up-axis))
        up (normalize (cross right dir))]
    [right up]))

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id) [:ability-data :skills :meltdowner :exp] 0.0)))

(defn- player-pos [player-id]
  (get (ps/get-player-state player-id) :position {:world-id "minecraft:overworld"
                                                  :x 0.0 :y 64.0 :z 0.0}))

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn meltdowner-cost-down-overload
  [{:keys [player-id]}]
  (lerp 200.0 170.0 (skill-exp player-id)))

(defn meltdowner-cost-tick-cp
  [{:keys [player-id ctx-id]}]
  (if-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
    (if (boolean (:performed? skill-state))
      0.0
      (double (or (:cp-per-tick skill-state)
                  (lerp 10.0 15.0 (skill-exp player-id)))))
    0.0))

(defn- enforce-overload-floor!
  [player-id floor-value]
  (ps/update-resource-data!
    player-id
    (fn [res-data]
      (if (< (double (:cur-overload res-data)) (double floor-value))
        (-> res-data
            (rdata/set-cur-overload floor-value)
            (assoc :overload-fine true))
        res-data))))

(defn- set-meltdowner-cooldown!
  [player-id exp ct]
  (let [rate (lerp 0.8 1.2 (/ (- (double ct) 20.0) 20.0))
        cd-ticks (int (* rate 20.0 (lerp 15.0 7.0 exp)))]
    (skill-effects/set-main-cooldown! player-id :meltdowner cd-ticks)))

(defn- add-meltdowner-exp!
  [player-id amount]
  (skill-effects/add-skill-exp! player-id :meltdowner amount))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-start {:mode :start}))

(defn- send-fx-update! [ctx-id ticks]
  (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-update
                           {:ticks (long ticks)
                            :charge-ratio (clamp01 (/ (double ticks) (double ticks-max)))}))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-end {:performed? (boolean performed?)}))

(defn- send-fx-perform!
  [ctx-id ct start end beam-length]
  (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-perform
                           {:charge-ticks (int ct)
                            :beam-length (double beam-length)
                            :start start
                            :end end}))

(defn- send-fx-reflect!
  [ctx-id start end]
  (ctx/ctx-send-to-client! ctx-id :meltdowner/fx-reflect
                           {:mode :reflect
                            :start start
                            :end end}))

(defn- to-charge-ticks [ticks]
  (int (min ticks-max (max ticks-min ticks))))

(defn- time-rate [ct]
  (lerp 0.8 1.2 (/ (- (double ct) 20.0) 20.0)))

(defn- get-energy [ct exp]
  (* (time-rate ct) (lerp 300.0 700.0 exp)))

(defn- get-damage [ct exp]
  (* (time-rate ct) (lerp 18.0 50.0 exp)))

(defn- get-exp-incr [ct]
  (* (time-rate ct) 0.002))

(defn- meltdowner-candidates
  [origin-player-id world-id start-pos dir beam-radius]
  (if-not world-effects/*world-effects*
    []
    (let [mid-pos (v+ start-pos (v* dir (/ max-increment 2.0)))]
      (->> (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                   world-id
                                                   (:x mid-pos) (:y mid-pos) (:z mid-pos)
                                                   (+ max-increment beam-radius))
           (remove (fn [{:keys [uuid]}] (= uuid origin-player-id)))
           (map (fn [{:keys [x y z] :as target}]
                  (let [to-target (v- {:x x :y y :z z} start-pos)
                        forward-dist (dot to-target dir)
                        closest-point (v+ start-pos (v* dir forward-dist))
                        radial-dist (distance-3d {:x x :y y :z z} closest-point)]
                    (assoc target :forward-dist forward-dist :radial-dist radial-dist))))
           (filter (fn [{:keys [forward-dist radial-dist]}]
                     (and (<= 0.0 forward-dist max-increment)
                          (<= radial-dist (* beam-radius 1.2)))))
           (sort-by (fn [{:keys [x y z]}]
                      (distance-sq-3d start-pos {:x x :y y :z z})))
           vec))))

(defn- compute-entity-damage [start-damage radial-distance]
  (let [dist (min max-increment (max 0.0 (double radial-distance)))
        factor (lerp 1.0 0.2 (/ dist max-increment))]
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
  [player-id world-id start-pos dir beam-radius max-distance energy]
  (when (and block-manip/*block-manipulation* (pos? energy) (pos? max-distance))
    (let [[right up] (orthonormal-basis dir)
          processed-starts (atom #{})
          sample-points (transient [])
          step 0.9]
      (doseq [s (range (- beam-radius) (+ beam-radius step) step)
              t (range (- beam-radius) (+ beam-radius step) step)]
        (let [jitter-radius (* beam-radius (+ 0.9 (* 0.2 (rand))))]
          (when (<= (+ (* s s) (* t t)) (* jitter-radius jitter-radius))
            (let [offset (v+ (v* right s) (v* up t))
                  origin (v+ start-pos offset)
                  key [(floor-int (:x origin)) (floor-int (:y origin)) (floor-int (:z origin))]]
              (when-not (contains? @processed-starts key)
                (swap! processed-starts conj key)
                (conj! sample-points origin))))))
      (let [origins (persistent! sample-points)
            line-energy (if (seq origins)
                          (/ (double energy) (double (count origins)))
                          0.0)
            neighbor-offsets [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]]]
        (doseq [origin origins]
          (loop [travel 0.0
                 remaining-energy (* line-energy (+ 0.95 (* 0.1 (rand))))
                 incrs 0]
            (when (and (<= travel max-distance)
                       (< incrs 50)
                       (pos? remaining-energy))
              (let [pos (v+ origin (v* dir travel))
                    bx (floor-int (:x pos))
                    by (floor-int (:y pos))
                    bz (floor-int (:z pos))
                    hardness (block-manip/get-block-hardness block-manip/*block-manipulation*
                                                             world-id bx by bz)
                    snd? (< incrs 20)]
                (if (or (nil? hardness) (neg? (double hardness)))
                  (recur (+ travel 1.0) remaining-energy (inc incrs))
                  (if (and (pos? (double hardness))
                           (<= (double hardness) remaining-energy)
                           (block-manip/can-break-block? block-manip/*block-manipulation*
                                                         player-id world-id bx by bz))
                    (do
                      (block-manip/break-block! block-manip/*block-manipulation*
                                                player-id world-id bx by bz (< (rand) 0.05))
                      (when (and snd? (< (rand) 0.05))
                        (let [[ox oy oz] (rand-nth neighbor-offsets)
                              nx (+ bx ox)
                              ny (+ by oy)
                              nz (+ bz oz)]
                          (when (block-manip/can-break-block? block-manip/*block-manipulation*
                                                              player-id world-id nx ny nz)
                            (block-manip/break-block! block-manip/*block-manipulation*
                                                      player-id world-id nx ny nz false))))
                      (recur (+ travel 1.0) (- remaining-energy (double hardness)) (inc incrs)))
                    (recur (+ travel 1.0) 0.0 (inc incrs))))))))))))

(defn- perform-reflected-strike!
  [ctx-id reflector-uuid exp]
  (let [reflect-damage (* 0.5 (lerp 20.0 50.0 exp))
        world-id (player-world-id reflector-uuid)
        reflector-pos (player-pos reflector-uuid)
        reflector-eye (eye-pos reflector-pos)
        look-vec (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* reflector-uuid))]
    (when look-vec
      (let [dir (normalize {:x (double (:x look-vec))
                            :y (double (:y look-vec))
                            :z (double (:z look-vec))})
            end-pos (v+ reflector-eye (v* dir 10.0))
            hit (when raycast/*raycast*
                  (raycast/raycast-entities raycast/*raycast*
                                            world-id
                                            (:x reflector-eye) (:y reflector-eye) (:z reflector-eye)
                                            (:x dir) (:y dir) (:z dir)
                                            10.0))]
        (send-fx-reflect! ctx-id reflector-eye end-pos)
        (when (and (= (:hit-type hit) :entity) entity-damage/*entity-damage*)
          (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                             world-id
                                             (:uuid hit)
                                             reflect-damage
                                             :magic))))))

(defn- perform-meltdowner!
  [{:keys [player-id ctx-id ticks exp]}]
  (let [ct (to-charge-ticks ticks)
        world-id (player-world-id player-id)
        origin-pos (player-pos player-id)
        damage-start origin-pos
        visual-start (eye-pos origin-pos)
        look-vec (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (if-not look-vec
      {:performed? false}
      (let [dir (normalize {:x (double (:x look-vec))
                            :y (double (:y look-vec))
                            :z (double (:z look-vec))})
            beam-radius (lerp 2.0 3.0 exp)
            energy (get-energy ct exp)
            start-damage (get-damage ct exp)
            candidates (meltdowner-candidates player-id world-id damage-start dir beam-radius)
            step-result (fn [{:keys [stop? reflection-distance-sq hit-count] :as acc}
                             {:keys [uuid x y z radial-dist]}]
                          (if stop?
                            (reduced acc)
                            (if (and (ps/get-player-state uuid)
                                     (vec-reflection-can-reflect? uuid start-damage))
                              (do
                                (perform-reflected-strike! ctx-id uuid exp)
                                (reduced {:stop? true
                                          :reflection-distance-sq (distance-sq-3d damage-start {:x x :y y :z z})
                                          :hit-count hit-count}))
                              (do
                                (when entity-damage/*entity-damage*
                                  (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                                     world-id
                                                                     uuid
                                                                     (compute-entity-damage start-damage radial-dist)
                                                                     :magic))
                                {:stop? false
                                 :reflection-distance-sq reflection-distance-sq
                                 :hit-count (inc hit-count)}))))
            result (reduce step-result
                           {:stop? false
                            :reflection-distance-sq nil
                            :hit-count 0}
                           candidates)
            beam-length (min 30.0 (double (or (:reflection-distance-sq result) 30.0)))
            block-distance (if-let [d2 (:reflection-distance-sq result)]
                             (min max-increment (Math/sqrt (double d2)))
                             max-increment)
            visual-end (v+ visual-start (v* dir beam-length))]
        (break-beam-blocks! player-id world-id damage-start dir beam-radius block-distance energy)
        (send-fx-perform! ctx-id ct visual-start visual-end beam-length)
        {:performed? true
         :ct ct
         :hit-count (:hit-count result)}))))

(defn meltdowner-on-key-down
  "Initialize Meltdowner charging."
  [{:keys [player-id ctx-id cost-ok?]}]
  (let [exp (skill-exp player-id)
          initial-overload (lerp 200.0 170.0 exp)]
      (if-not cost-ok?
        (do
          (send-fx-end! ctx-id false)
          (ctx/terminate-context! ctx-id nil)
          (log/debug "Meltdowner start failed: insufficient resource"))
        (do
          (ctx/update-context! ctx-id assoc :skill-state
                               {:ticks 0
                                :overload-floor initial-overload
                                :cp-per-tick (lerp 10.0 15.0 exp)
                                :performed? false})
          (send-fx-start! ctx-id)
          (send-fx-update! ctx-id 0)
          (log/debug "Meltdowner charge started"))))

(defn meltdowner-on-key-tick
  "Advance charging; consume CP and enforce overload floor."
  [{:keys [player-id ctx-id cost-ok?]}]
  (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
      (let [performed? (boolean (:performed? skill-state))]
        (when-not performed?
          (let [ticks (inc (int (or (:ticks skill-state) 0)))
                overload-floor (double (or (:overload-floor skill-state) 0.0))]
            (enforce-overload-floor! player-id overload-floor)
            (ctx/update-context! ctx-id assoc-in [:skill-state :ticks] ticks)
            (send-fx-update! ctx-id ticks)

            (cond
              (> ticks ticks-tolerant)
              (do
                (send-fx-end! ctx-id false)
                (ctx/terminate-context! ctx-id nil)
                (log/debug "Meltdowner aborted: over tolerant ticks" ticks))

              (not cost-ok?)
              (do
                (send-fx-end! ctx-id false)
                (ctx/terminate-context! ctx-id nil)
                (log/debug "Meltdowner aborted: insufficient CP at tick" ticks))

              (zero? (mod ticks 20))
              (log/debug "Meltdowner charging:" (int (* 100.0 (clamp01 (/ ticks ticks-max)))) "%"))))))

(defn meltdowner-on-key-up
  "Perform Meltdowner on release if minimum charge reached."
  [{:keys [player-id ctx-id]}]
  (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
      (let [ticks (int (or (:ticks skill-state) 0))
            performed? (boolean (:performed? skill-state))
            exp (skill-exp player-id)]
        (when-not performed?
          (if (< ticks ticks-min)
            (do
              (send-fx-end! ctx-id false)
              (log/debug "Meltdowner: insufficient charge ticks" ticks))
            (let [{:keys [performed? ct hit-count]} (perform-meltdowner!
                                                      {:player-id player-id
                                                       :ctx-id ctx-id
                                                       :ticks ticks
                                                       :exp exp})]
              (if performed?
                (do
                  (add-meltdowner-exp! player-id (get-exp-incr ct))
                  (set-meltdowner-cooldown! player-id exp ct)
                  (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] true)
                  (send-fx-end! ctx-id true)
                  (log/debug "Meltdowner performed: ct" ct "hits" hit-count))
                (do
                  (send-fx-end! ctx-id false)
                  (log/debug "Meltdowner perform failed"))))))))

(defn meltdowner-on-key-abort
  "Clean up charge state on abort."
  [{:keys [ctx-id]}]
  (send-fx-end! ctx-id false)
  (ctx/update-context! ctx-id dissoc :skill-state)
  (log/debug "Meltdowner charge aborted")))

(defskill! meltdowner
  :id :meltdowner
  :category-id :meltdowner
  :name-key "ability.skill.meltdowner.meltdowner"
  :description-key "ability.skill.meltdowner.meltdowner.desc"
  :icon "textures/abilities/meltdowner/skills/meltdowner.png"
  :level 1
  :controllable? true
  :ctrl-id :meltdowner
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 200
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:overload meltdowner-cost-down-overload}
         :tick {:cp meltdowner-cost-tick-cp}}
  :actions {:down! meltdowner-on-key-down
            :tick! meltdowner-on-key-tick
            :up! meltdowner-on-key-up
            :abort! meltdowner-on-key-abort})
