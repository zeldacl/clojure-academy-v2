(ns cn.li.ac.content.ability.electromaster.railgun
  "Railgun skill 锟?coin-QTE + iron-item charge mechanic.

  Complex skill using the escape-hatch pattern: fn hooks for the custom
  coin-QTE / item-charge logic; :beam op (effect.beam) for the actual shot.

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.item-actions :as item-actions]
                        [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private accepted-item-ids
  #{"minecraft:iron_ingot" "minecraft:iron_block"})

(def ^:private railgun-skill-id :railgun)

(defn- cfg-double [field-id]
  (skill-config/tunable-double railgun-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int railgun-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double railgun-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int railgun-skill-id field-id exp))

(defn- coin-active-threshold [] (cfg-double :qte.coin-active-threshold))
(defn- coin-perform-threshold [] (cfg-double :qte.coin-perform-threshold))
(defn- item-charge-ticks [] (cfg-int :charge.item-charge-ticks))
(defn- reflection-distance [] (cfg-double :reflection.distance))
(defn- reflection-damage [] (cfg-double :reflection.damage))
(defn- railgun-exp-gain [hit? reflection-hit?]
  ;; Original: hitEntity (normal OR reflection hit) 锟?0.01, miss 锟?0.005
  (if (or hit? reflection-hit?)
    (cfg-double :progression.exp-reflection-hit)
    (cfg-double :progression.exp-hit)))
(defn- railgun-cooldown-ticks [exp]
  (cfg-lerp-int :cooldown.manual-ticks exp))

;; ---------------------------------------------------------------------------
;; Player / item helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id :railgun))

(defn- body-pos [player-id]
  (geom/body-pos player-id))

(defn- accepted-item-in-hand? [player]
  (when player
    (contains? accepted-item-ids (entity/player-get-main-hand-item-id player))))

(defn- consume-item-for-shot! [player]
  (if (or (nil? player) (entity/player-creative? player))
    true
    (entity/player-consume-main-hand-item! player 1)))

(defn- set-skill-state!
  [ctx-id k v]
  (ctx-skill/assoc-skill-state! ctx-id k v))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (ctx-skill/update-skill-state-root! ctx-id identity state-map))

(defn- clear-skill-state!
  [ctx-id]
  (ctx-skill/clear-skill-state! ctx-id))

(defn register-coin-throw!
  "Register a railgun coin throw state.
  Called from the platform item-action hook when a coin is used in ability mode.
  If the player is currently in item-charge mode, that charge is aborted first
  (mirrors original informThrowCoin 锟?onKeyAbort() behavior)."
  [player-id payload]
  (let [_now-ms (long (or (:timestamp-ms payload) (System/currentTimeMillis)))]
    ;; Abort any in-progress item charge so the coin QTE takes priority.
    (doseq [[ctx-id ctx-data] (ctx/get-all-contexts)]
      (when (and (= (:player-uuid ctx-data) player-id)
                 (= :item-charge (get-in ctx-data [:skill-state :mode])))
        (set-skill-state-root! ctx-id {:fired false
                                       :mode :item-charge-cancelled
                                       :charge-ticks 0})))
    ;; New coin throw resets one-shot judgement lock.
    (skill-effects/clear-railgun-coin-judged! player-id)
    true))

(defn- discard-coin-entity!
  "Kills a specific coin entity by UUID. Falls back to nearby cleanup when UUID is nil."
  [player-id coin-uuid]
  (when (and world-effects/*world-effects* entity-motion/*entity-motion*)
    (let [pos (geom/eye-pos player-id)
          world-id (geom/world-id-of player-id)]
      (when (and pos world-id)
        (doseq [ent (world-effects/find-entities-in-radius
                      world-effects/*world-effects*
                      world-id (:x pos) (:y pos) (:z pos) 4.0)]
          (when (and (= "entity_coin_throwing" (:type ent))
                     (or (nil? coin-uuid)
                         (= coin-uuid (:uuid ent))))
            (entity-motion/discard-entity! entity-motion/*entity-motion*
                                           world-id (:uuid ent))))))))

(defn- qte-status [p]
  (let [progress (double (max 0.0 (min 1.0 (or p 0.0))))]
    {:has-window? true
     :progress    progress
     :active?     (>= progress (coin-active-threshold))
     :perform?    (> progress (coin-perform-threshold))}))

(defn- coin-candidates [entities]
  (->> entities
       (filter #(= "entity_coin_throwing" (:type %)))
      (sort-by (fn [ent] (double (or (:motion-progress ent) 0.0))) >)))

(defn- coin-judged-uuid [player-id]
  (skill-effects/player-path player-id [:runtime :railgun :coin-judged-uuid]))

(defn- mark-coin-judged! [player-id coin-uuid]
  (when (some? coin-uuid)
    (skill-effects/mark-railgun-coin-judged! player-id coin-uuid)))

(defn- read-coin-qte-status [player-id]
  (if-not world-effects/*world-effects*
    {:has-window? false :active? false :perform? false :progress 0.0}
    (let [pos (geom/eye-pos player-id)
          world-id (geom/world-id-of player-id)
          entities (when (and pos world-id)
                     (world-effects/find-entities-in-radius
                      world-effects/*world-effects*
                      world-id (:x pos) (:y pos) (:z pos) 4.0))
          judged-uuid (coin-judged-uuid player-id)
          candidates (coin-candidates entities)
          coin (some (fn [ent]
                       (when (not= judged-uuid (:uuid ent)) ent))
                     candidates)]
      (if coin
        (assoc (qte-status (double (or (:motion-progress coin) 0.0)))
               :coin-uuid (:uuid coin))
        (do
          ;; Clear stale lock when all coins are gone.
          (skill-effects/clear-railgun-coin-judged! player-id)
          {:has-window? false :active? false :perform? false :progress 0.0})))))

;; ---------------------------------------------------------------------------
;; Vec-reflection interaction
;; ---------------------------------------------------------------------------

(defn- toggle-active? [player-id skill-id]
  (some (fn [[_ ctx-data]]
          (and (= (:player-uuid ctx-data) player-id)
               (toggle/is-toggle-active? ctx-data skill-id)))
        (ctx/get-all-contexts)))

(defn- vec-reflection-can-reflect? [target-player-id incoming-damage]
  (when (toggle-active? target-player-id :vec-reflection)
    (when-let [state (skill-effects/get-player-state target-player-id)]
      (let [exp        (skill-effects/skill-exp target-player-id :vec-reflection)
            consumption (* (double incoming-damage)
                           (cfg-lerp :reflection.cp-consumption-per-damage exp))
            current-cp (get-in state [:resource-data :cur-cp] 0.0)]
        (>= (double current-cp) (double consumption))))))

(defn- vec-reflection-consume-cp! [target-player-id incoming-damage]
  (when-let [state (skill-effects/get-player-state target-player-id)]
    (let [exp        (skill-effects/skill-exp target-player-id :vec-reflection)
          consumption (* (double incoming-damage)
                         (cfg-lerp :reflection.cp-consumption-per-damage exp))]
      (skill-effects/perform-resource! target-player-id 0.0 (double consumption) false))))

(defn- perform-reflection-shot!
  "Fire a secondary shot from the reflector player's perspective.
  Returns truthy if an entity was hit."
  [ctx-id reflector-player-id]
  (let [start-pos (geom/eye-pos reflector-player-id)
        world-id  (geom/world-id-of reflector-player-id)
        look-vec  (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* reflector-player-id))]
    (when look-vec
      (let [max-distance (reflection-distance)
            hit (raycast/raycast-entities raycast/*raycast*
                                          world-id
                                          (:x start-pos) (:y start-pos) (:z start-pos)
                                          (:dx look-vec) (:dy look-vec) (:dz look-vec)
                                          max-distance)
            actual-dist (if (= (:hit-type hit) :entity)
                          (double (or (:distance hit) max-distance))
                          max-distance)
            dir         {:x (:dx look-vec) :y (:dy look-vec) :z (:dz look-vec)}
            end-pos     (geom/v+ start-pos (geom/v* dir actual-dist))]
        (ctx/ctx-send-to-client! ctx-id :railgun/fx-reflect
                                 {:mode         :reflect
                                  :start        start-pos
                                  :end          end-pos
                                  :hit-distance actual-dist})
        (when (and (= (:hit-type hit) :entity) entity-damage/*entity-damage*)
          (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                              world-id (:uuid hit)
                                              (reflection-damage) :magic)
          true)))))

;; ---------------------------------------------------------------------------
;; Main beam shot
;; ---------------------------------------------------------------------------

(defn- perform-main-shot!
  "Fires the railgun beam. Returns :beam-result map (or {:performed? false})."
  [player-id ctx-id exp]
  (let [world-id  (geom/world-id-of player-id)
        eye       (geom/eye-pos player-id)
        trace-pos (body-pos player-id)
        look-vec  (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (if-not look-vec
      {:performed? false}
      (let [damage   (cfg-lerp :beam.damage exp)
                result   (beam/execute-beam!
                    {:player-id       player-id
                     :ctx-id          ctx-id
                     :world-id        world-id
                     :eye-pos         eye
                     :trace-pos       trace-pos
                     :look-dir        look-vec
                     :reflect-can-fn  (fn [uuid] (vec-reflection-can-reflect? uuid damage))
                     :reflect-shot-fn (fn [uuid]
                      (vec-reflection-consume-cp! uuid damage)
                      (perform-reflection-shot! ctx-id uuid))}
                    {:radius          (cfg-double :beam.radius)
                     :query-radius    (cfg-double :beam.query-radius)
                     :step            (cfg-double :beam.step)
                     :max-distance    (cfg-double :beam.max-distance)
                     :visual-distance (cfg-double :beam.visual-distance)
                     :damage          damage
                     :damage-type     :magic
                     :break-blocks?   true
                     :block-energy    (cfg-lerp :beam.block-energy exp)
                     :fx-topic        :railgun/fx-shot})
            beam-result (or (:beam-result result) {:performed? false})]
        (when (and (:performed? beam-result) world-effects/*world-effects*)
          (world-effects/play-sound! world-effects/*world-effects*
                                     world-id
                                     (:x eye) (:y eye) (:z eye)
                                     "my_mod:em.railgun"
                                     :ambient
                                     0.5
                                     1.0))
        beam-result))))

(defn- creeper-hit?
  [world-id hit-uuids]
  (boolean
    (some (fn [entity-uuid]
            (= "minecraft:creeper"
               (entity/entity-get-type-id* world-id entity-uuid)))
          hit-uuids)))

;; ---------------------------------------------------------------------------
;; Cost hooks (private, passed as fns in defskill)
;; ---------------------------------------------------------------------------

(defn- item-charge-ready? [ctx-id player]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx-data)]
      (and (= (:mode skill-state) :item-charge)
           (<= (max 0 (int (or (:charge-ticks skill-state) 0))) 1)
           (accepted-item-in-hand? player)))))

(defn- cost-creative?      [{:keys [player]}]
  (boolean (and player (entity/player-creative? player))))

(defn- down-cost-cp        [{:keys [player-id]}]
  (let [qte (read-coin-qte-status player-id)]
    (if (:perform? qte) (cfg-lerp :cost.down.cp (skill-exp player-id)) 0.0)))

(defn- down-cost-overload  [{:keys [player-id]}]
  (let [qte (read-coin-qte-status player-id)]
    (if (:perform? qte) (cfg-lerp :cost.down.overload (skill-exp player-id)) 0.0)))

(defn- tick-cost-cp        [{:keys [player-id ctx-id player]}]
  (if (item-charge-ready? ctx-id player)
    (cfg-lerp :cost.tick.cp (skill-exp player-id))
    0.0))

(defn- tick-cost-overload  [{:keys [player-id ctx-id player]}]
  (if (item-charge-ready? ctx-id player)
    (cfg-lerp :cost.tick.overload (skill-exp player-id))
    0.0))

;; ---------------------------------------------------------------------------
;; Action handlers
;; ---------------------------------------------------------------------------

(defn- railgun-on-key-down
  "Coin-QTE path fires immediately; otherwise starts 20-tick iron-item charge."
  [{:keys [player-id ctx-id player cost-ok?]}]
  (let [exp    (skill-exp player-id)
        qte    (read-coin-qte-status player-id)]
    (cond
      (:perform? qte)
      (do
        (mark-coin-judged! player-id (:coin-uuid qte))
        ;; Kill the coin entity unconditionally (mirrors coin.setDead() before performServer).
        (discard-coin-entity! player-id (:coin-uuid qte))
        (if cost-ok?
          (let [{:keys [performed? reflection-hit? normal-hit-count hit-uuids]} (perform-main-shot! player-id ctx-id exp)]
            (when performed?
              (skill-effects/add-skill-exp! player-id :railgun (railgun-exp-gain (pos? (long (or normal-hit-count 0))) reflection-hit?))
              (when (and (pos? (long (or normal-hit-count 0)))
                         (creeper-hit? (geom/world-id-of player-id) hit-uuids))
                (ach-dispatcher/trigger-custom-event! player-id "electromaster.attack_creeper"))
              (skill-effects/set-main-cooldown! player-id :railgun
                                                (railgun-cooldown-ticks exp))
              (set-skill-state-root! ctx-id
                                     {:fired       true
                                      :mode        :performed
                                      :hit-count   normal-hit-count}))
            (log/debug "Railgun coin-QTE perform" player-id))
          (set-skill-state-root! ctx-id {:fired false :mode :coin-qte-no-resource})))

      (:has-window? qte)
      (do
        (mark-coin-judged! player-id (:coin-uuid qte))
        (set-skill-state-root! ctx-id {:fired false :mode :coin-qte-miss})
        (log/debug "Railgun coin-QTE miss" player-id (:progress qte)))

      (accepted-item-in-hand? player)
      (set-skill-state-root! ctx-id
                             {:fired        false
                              :mode         :item-charge
                              :charge-ticks (item-charge-ticks)
                              :hit-count    0})

      :else
      (set-skill-state-root! ctx-id {:fired false :mode :idle-no-trigger}))))

(defn- railgun-on-key-tick
  "Item-charge path: countdown; auto-fires when charge-ticks reaches zero."
  [{:keys [player-id ctx-id player cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)]
        (when (= (:mode skill-state) :item-charge)
          (let [ticks-left (max 0 (int (or (:charge-ticks skill-state) 0)))]
            (if (<= ticks-left 1)
              (do
                (if (and (accepted-item-in-hand? player)
                         (consume-item-for-shot! player)
                         cost-ok?)
                  (let [exp (skill-exp player-id)
                        {:keys [performed? reflection-hit? normal-hit-count hit-uuids]}
                        (perform-main-shot! player-id ctx-id exp)]
                    (when performed?
                      (skill-effects/add-skill-exp! player-id :railgun (railgun-exp-gain (pos? (long (or normal-hit-count 0))) reflection-hit?))
                      (when (and (pos? (long (or normal-hit-count 0)))
                                 (creeper-hit? (geom/world-id-of player-id) hit-uuids))
                        (ach-dispatcher/trigger-custom-event! player-id "electromaster.attack_creeper"))
                      (skill-effects/set-main-cooldown! player-id :railgun
                                                        (railgun-cooldown-ticks exp))
                      (set-skill-state-root! ctx-id
                                             {:fired     true
                                              :mode      :performed
                                              :hit-count normal-hit-count})))
                  (set-skill-state-root! ctx-id
                                         (assoc skill-state :fired false :mode :item-charge-failed)))
                (set-skill-state! ctx-id [:charge-ticks] 0))
              (set-skill-state! ctx-id [:charge-ticks] (dec ticks-left)))))))
    (catch Exception e
      (log/warn "railgun-on-key-tick error" (ex-message e)))))

(defn- railgun-on-key-up
  "Cancels an unfinished item charge. Cooldown is only applied on successful perform."
  [{:keys [ctx-id]}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          mode        (:mode skill-state)]
      (when (and (= mode :item-charge) (not (:fired skill-state)))
        (set-skill-state-root! ctx-id
                               (assoc skill-state :mode :item-charge-cancelled :charge-ticks 0)))
      (when (:fired skill-state)
        (log/debug "Railgun completed")))))

(defn- railgun-on-key-abort
  [{:keys [ctx-id]}]
  (clear-skill-state! ctx-id)
  (log/debug "Railgun aborted"))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(declare railgun)

(defskill railgun
  :id              :railgun
  :category-id     :electromaster
  :name-key        "ability.skill.electromaster.railgun"
  :description-key "ability.skill.electromaster.railgun.desc"
  :icon            "textures/abilities/electromaster/skills/railgun.png"
  :ui-position     [164 59]
  :level           3
  :controllable?   true
  :ctrl-id         :railgun
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks  1
  :pattern         :charge-window
  :cooldown        {:mode :manual}
  :cost            {:down {:cp       down-cost-cp
                           :overload down-cost-overload
                           :creative? cost-creative?}
                    :tick {:cp       tick-cost-cp
                           :overload tick-cost-overload
                           :creative? cost-creative?}}
  :actions         {:down!  railgun-on-key-down
                    :tick!  railgun-on-key-tick
                    :up!    railgun-on-key-up
                    :abort! railgun-on-key-abort}
  :prerequisites   [{:skill-id :thunder-bolt :min-exp 0.3}
                    {:skill-id :mag-manip    :min-exp 1.0}])

(defn init!
  []
  (item-actions/register-item-action! "ac:coin" :railgun-coin-throw)
  (item-actions/register-item-action! "my_mod:coin" :railgun-coin-throw)
  (item-actions/register-action-handler! :railgun-coin-throw register-coin-throw!)
  (item-actions/register-item-entity-spawn! "ac:coin" {:entity-id "entity_coin_throwing" :speed 0.0})
  (item-actions/register-item-entity-spawn! "my_mod:coin" {:entity-id "entity_coin_throwing" :speed 0.0})
  nil)


