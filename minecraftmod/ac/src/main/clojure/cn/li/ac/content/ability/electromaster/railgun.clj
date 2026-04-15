(ns cn.li.ac.content.ability.electromaster.railgun
  "Railgun skill - reflectible beam attack with dual activation modes.

  Mechanics:
  - Dual activation: throw coin for QTE window OR charge with iron ingot/block in hand
  - Energy: 180-120 overload + 200-450 CP + 900-2000 per shot (scales with exp)
  - Damage: 60-110 (scales with exp)
  - Cooldown: 300-160 ticks
  - Reflection: Damage bounces off entities (50% per bounce, max 3 reflections)
  - Grants 0.01 exp on entity hits

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.reflection :as reflection]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(def ^:private accepted-item-ids
  #{"minecraft:iron_ingot" "minecraft:iron_block"})

(def ^:private coin-window-ms 1000)
(def ^:private coin-active-threshold 0.6)
(def ^:private coin-perform-threshold 0.7)
(def ^:private item-charge-ticks 20)

(defn- lerp
  [a b t]
  (+ a (* (- b a) (double t))))

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

(defn- consume-railgun-cost!
  [player-id player exp]
  (when-let [state (ps/get-player-state player-id)]
    (let [cp (lerp 200.0 450.0 exp)
          overload (lerp 180.0 120.0 exp)
          creative? (boolean (and player (entity/player-creative? player)))
          {:keys [data success? events]} (res/perform-resource
                                           (:resource-data state)
                                           player-id
                                           overload
                                           cp
                                           creative?)]
      (when success?
        (ps/update-resource-data! player-id (constantly data))
        (doseq [e events]
          (ability-evt/fire-ability-event! e)))
      (boolean success?))))

(defn- apply-railgun-cooldown!
  [player-id exp]
  (let [cd-ticks (int (Math/round (lerp 300.0 160.0 exp)))]
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

(defn- perform-railgun-shot!
  [player-id ctx-id exp]
  (let [base-range 45.0
        look-vec (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))
        player-state (ps/get-player-state player-id)
        player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]
    (if-not look-vec
      (do
        (ctx/update-context! ctx-id assoc :skill-state {:skip-default-cooldown true :fired false})
        false)
      (let [{:keys [x y z]} player-pos
            {:keys [dx dy dz]} look-vec
            start-pos {:x x :y (+ y 1.6) :z z}
            hit (when raycast/*raycast*
                  (raycast/raycast-combined raycast/*raycast*
                                            "minecraft:overworld"
                                            (:x start-pos) (:y start-pos) (:z start-pos)
                                            dx dy dz
                                            base-range))]
        (if (nil? hit)
          (do
            (ctx/update-context! ctx-id assoc :skill-state {:skip-default-cooldown true :fired false})
            false)
          (let [hit-type (:hit-type hit)
                hit-distance (double (or (:distance hit) base-range))
                end-pos {:x (+ (:x start-pos) (* dx hit-distance))
                         :y (+ (:y start-pos) (* dy hit-distance))
                         :z (+ (:z start-pos) (* dz hit-distance))}
                base-damage (lerp 60.0 110.0 exp)
                hit-entities (if (and (= hit-type :entity) entity-damage/*entity-damage*)
                               (entity-damage/apply-reflection-damage!
                                 entity-damage/*entity-damage*
                                 "minecraft:overworld"
                                 (:uuid hit)
                                 base-damage
                                 :magic
                                 0
                                 2)
                               [])
                hit-count (count hit-entities)
                exp-gain (if (pos? hit-count) 0.01 0.005)]
            (ctx/ctx-send-to-client! ctx-id :railgun/fx-shot
                                     {:mode (if (= hit-type :entity) :entity-hit :block-hit)
                                      :start start-pos
                                      :end end-pos
                                      :hit-distance hit-distance
                                      :hit-count hit-count})
            (add-railgun-exp! player-id exp-gain)
            (apply-railgun-cooldown! player-id exp)
            (ctx/update-context! ctx-id assoc :skill-state
                                 {:skip-default-cooldown true
                                  :fired true
                                  :hit-count hit-count
                                  :mode :performed})
            true))))))

(defn railgun-on-key-down
  "Railgun activation logic.
  1) Coin QTE perform when coin window progress > 0.7
  2) Otherwise start 20-tick iron-item charge"
  [{:keys [player-id ctx-id player]}]
  (try
    (let [exp (get-skill-exp player-id)
          now-ms (System/currentTimeMillis)
          qte (consume-coin-qte-window! player-id now-ms)]
      (cond
        (:perform? qte)
        (if (consume-railgun-cost! player-id player exp)
          (do
            (perform-railgun-shot! player-id ctx-id exp)
            (log/debug "Railgun coin-QTE perform" player-id))
          (ctx/update-context! ctx-id assoc :skill-state {:skip-default-cooldown true :fired false :mode :coin-qte-no-resource}))

        (:has-window? qte)
        (do
          (ctx/update-context! ctx-id assoc :skill-state {:skip-default-cooldown true :fired false :mode :coin-qte-miss})
          (log/debug "Railgun coin-QTE miss" player-id (:progress qte)))

        (accepted-item-in-hand? player)
        (ctx/update-context! ctx-id assoc :skill-state
                             {:skip-default-cooldown true
                              :fired false
                              :mode :item-charge
                              :charge-ticks item-charge-ticks
                              :hit-count 0})

        :else
        (ctx/update-context! ctx-id assoc :skill-state {:skip-default-cooldown true :fired false :mode :idle-no-trigger})))
    (catch Exception e
      (log/warn "Railgun key-down failed:" (ex-message e)))))

(defn railgun-on-key-tick
  "Item-charge path: countdown to automatic perform at 20 ticks."
  [{:keys [player-id ctx-id player]}]
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
                         (consume-railgun-cost! player-id player (get-skill-exp player-id)))
                  (perform-railgun-shot! player-id ctx-id (get-skill-exp player-id))
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
