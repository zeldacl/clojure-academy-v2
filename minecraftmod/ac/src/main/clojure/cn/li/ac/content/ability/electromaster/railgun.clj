(ns cn.li.ac.content.ability.electromaster.railgun
  "Railgun skill �?coin-QTE + iron-item charge mechanic.

  Complex skill using the escape-hatch pattern: fn hooks for the custom
  coin-QTE / item-charge logic; :beam op (effect.beam) for the actual shot.

  No Minecraft imports."
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.shared.vec-reflection-interaction :as vec-reflect]
            [cn.li.ac.ability.item-actions :as item-actions]
                        [cn.li.ac.ability.effects.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.ability.effects.damage :as entity-damage]
            [cn.li.ac.ability.effects.motion :as motion-effects]
            [cn.li.ac.ability.effects.world :as world-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def-skill-config-ops :railgun)
(def ^:private accepted-item-ids
  #{"minecraft:iron_ingot" "minecraft:iron_block"})

(defn- coin-active-threshold [] (cfg-double :qte.coin-active-threshold))
(defn- coin-perform-threshold [] (cfg-double :qte.coin-perform-threshold))
(defn- item-charge-ticks [] (cfg-int :charge.item-charge-ticks))
(defn- reflection-distance [] (cfg-double :reflection.distance))
(defn- reflection-damage [] (cfg-double :reflection.damage))
(defn- railgun-exp-gain [_hit? reflection-hit?]
  ;; Original: hitEntity only set on reflection hit �?0.01; otherwise 0.005
  (if reflection-hit?
    (cfg-double :progression.exp-reflection-hit)   ;; 0.01 reflection hit only
    (cfg-double :progression.exp-hit)))             ;; 0.005 normal hit or miss
(defn- railgun-cooldown-ticks [exp]
  (cfg-lerp-int :cooldown.manual-ticks exp))

;; ---------------------------------------------------------------------------
;; Player / item helpers
;; ---------------------------------------------------------------------------

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

(defn register-coin-throw!
  "Register a railgun coin throw state.
  Called from the platform item-action hook when a coin is used in ability mode.
  If the player is currently in item-charge mode, that charge is aborted first
  (mirrors original informThrowCoin �?onKeyAbort() behavior)."
  [player-id payload]
  (let [_now-ms (long (or (:timestamp-ms payload) (System/currentTimeMillis)))]
    ;; Abort any in-progress item charge so the coin QTE takes priority.
    (doseq [[_ctx-key ctx-data] (ctx/get-all-contexts)]
      (when (and (= (:player-uuid ctx-data) player-id)
                 (= :item-charge (get-in ctx-data [:skill-state :mode])))
        (ctx-skill/replace-skill-state! (:id ctx-data) {:fired false
                                       :mode :item-charge-cancelled
                                       :charge-ticks 0})))
    ;; New coin throw resets one-shot judgement lock.
    (skill-effects/clear-railgun-coin-judged! player-id)
    true))

(def ^:private coin-entity-registry-id (modid/namespaced-path "entity_coin_throwing"))

(defn- coin-entity?
  "Whether ent (a find-entities-in-radius* result map) is the coin-throwing
  entity. Resolved via entity-get-type-id* (registry-key format, e.g.
  \"modid:entity_coin_throwing\") rather than the ad-hoc :type/:entity-id
  fields on the entity map, whose format differs across Forge (registry key)
  and Fabric (description id) and never equals the bare registry name either
  way."
  [world-id ent]
  (= coin-entity-registry-id (entity/entity-get-type-id* world-id (:uuid ent))))

(defn- discard-coin-entity!
  "Kills a specific coin entity by UUID. Falls back to nearby cleanup when UUID is nil."
  [player-id coin-uuid]
  (when (and (world-effects/available?) (motion-effects/entity-motion-available?))
    (let [pos (geom/eye-pos player-id)
          world-id (geom/world-id-of player-id)]
      (when (and pos world-id)
        (doseq [ent (world-effects/find-entities-in-radius
                      world-id (:x pos) (:y pos) (:z pos) 4.0)]
          (when (and (coin-entity? world-id ent)
                     (or (nil? coin-uuid)
                         (= coin-uuid (:uuid ent))))
            (motion-effects/discard-entity!
                                           world-id (:uuid ent))))))))

(defn- qte-status [p]
  (let [progress (double (max 0.0 (min 1.0 (or p 0.0))))]
    {:has-window? true
     :progress    progress
     :active?     (>= progress (coin-active-threshold))
     :perform?    (> progress (coin-perform-threshold))}))

(defn- coin-candidates [world-id entities]
  (->> entities
       (filter (partial coin-entity? world-id))
      (sort-by (fn [ent] (double (or (:motion-progress ent) 0.0))) >)))

(defn- coin-judged-uuid [player-id]
  (skill-effects/player-path player-id [:runtime :railgun :coin-judged-uuid]))

(defn- mark-coin-judged! [player-id coin-uuid]
  (when (some? coin-uuid)
    (skill-effects/mark-railgun-coin-judged! player-id coin-uuid)))

(defn- read-coin-qte-status [player-id]
  (if-not (world-effects/available?)
    {:has-window? false :active? false :perform? false :progress 0.0}
    (let [pos (geom/eye-pos player-id)
          world-id (geom/world-id-of player-id)
          entities (when (and pos world-id)
                     (world-effects/find-entities-in-radius
                      world-id (:x pos) (:y pos) (:z pos) 4.0))
          judged-uuid (coin-judged-uuid player-id)
          candidates (coin-candidates world-id entities)
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

(defn- perform-reflection-shot!  "Fire a secondary shot from the reflector player's perspective.
  Returns truthy if an entity was hit."
  [ctx-id reflector-player-id]
  (let [start-pos (geom/eye-pos reflector-player-id)
        world-id  (geom/world-id-of reflector-player-id)
        look-vec  (when (raycast/available?)
                    (raycast/player-look-vector reflector-player-id))]
    (when look-vec
      (let [max-distance (reflection-distance)
            ;; player-look-vector only ever returns {:x :y :z} — there is
            ;; no :dx/:dy/:dz key on this bridge's result.
            look-x (double (or (:x look-vec) 0.0))
            look-y (double (or (:y look-vec) 0.0))
            look-z (double (or (:z look-vec) 1.0))
            hit (raycast/raycast-entities
                                          world-id
                                          (:x start-pos) (:y start-pos) (:z start-pos)
                                          look-x look-y look-z
                                          max-distance)
            ;; raycast-entities never sets :hit-type (that key only appears
            ;; on raycast-combined results) — a real hit is just non-nil,
            ;; identified by :uuid.
            hit-uuid    (:uuid hit)
            actual-dist (if hit-uuid
                          (double (or (:distance hit) max-distance))
                          max-distance)
            dir         {:x look-x :y look-y :z look-z}
            end-pos     (geom/v+ start-pos (geom/v* dir actual-dist))]
        (fx/send! ctx-id {:topic :railgun/fx-reflect :mode :reflect} nil {:start        start-pos
                                  :end          end-pos
                                  :hit-distance actual-dist})
        (when (and hit-uuid (entity-damage/available?))
          (entity-damage/apply-direct-damage!
                                              world-id hit-uuid
                                              (reflection-damage) :generic)
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
        look-vec  (when (raycast/available?)
                    (raycast/player-look-vector player-id))]
    (if-not look-vec
      {:performed? false}
      (let [damage   (cfg-lerp :beam.damage exp)
            reflection (vec-reflect/build-reflection-callbacks
                         {:ctx-id ctx-id
                          :caster-skill-id :railgun
                          :cp-field-id :reflection.cp-consumption-per-damage
                          :reflect-shot-fn perform-reflection-shot!})
                result   (beam/execute-beam!
                    (merge {:player-id       player-id
                            :ctx-id          ctx-id
                            :world-id        world-id
                            :eye-pos         eye
                            :trace-pos       trace-pos
                            :look-dir        look-vec}
                           reflection)                    {:radius          (cfg-double :beam.radius)
                     :query-radius    (cfg-double :beam.query-radius)
                     :step            (cfg-double :beam.step)
                     :max-distance    (cfg-double :beam.max-distance)
                     :visual-distance (cfg-double :beam.visual-distance)
                     :damage          damage
                     :damage-type     :generic
                     :break-blocks?   true
                     :block-energy    (cfg-lerp :beam.block-energy exp)
                     :fx-topic        :railgun/fx-shot})
            beam-result (or (:beam-result result) {:performed? false})]
        (when (and (:performed? beam-result) (world-effects/available?))
          (world-effects/play-sound!
                                     world-id
                                     (:x eye) (:y eye) (:z eye)
                                     (modid/namespaced-path "em.railgun")
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

(defn- active-railgun-ctx-id [player-id]
  (some (fn [[ctx-id ctx-data]]
          (when (and (= (:player-uuid ctx-data) player-id)
                     (= :railgun (:skill-id ctx-data)))
            ctx-id))
        (ctx/get-all-contexts)))

(defn- item-charge-at-fire-point? [ctx-id]
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
    (let [skill-state (:skill-state ctx-data)]
      (and (= (:mode skill-state) :item-charge)
           (<= (max 0 (int (or (:charge-ticks skill-state) 0))) 1)))))

(defn- item-charge-ready? [ctx-id player]
  (and (item-charge-at-fire-point? ctx-id)
       (accepted-item-in-hand? player)))

(defn- cost-creative? [_player-id _skill-id _exp]
  false)

(defn- down-cost-cp [player-id _skill-id exp]
  (let [qte (read-coin-qte-status player-id)]
    (if (:perform? qte) (cfg-lerp :cost.down.cp exp) 0.0)))

(defn- down-cost-overload [player-id _skill-id exp]
  (let [qte (read-coin-qte-status player-id)]
    (if (:perform? qte) (cfg-lerp :cost.down.overload exp) 0.0)))

(defn- tick-cost-cp [player-id _skill-id exp]
  (if-let [ctx-id (active-railgun-ctx-id player-id)]
    (if (item-charge-at-fire-point? ctx-id)
      (cfg-lerp :cost.tick.cp exp)
      0.0)
    0.0))

(defn- tick-cost-overload [player-id _skill-id exp]
  (if-let [ctx-id (active-railgun-ctx-id player-id)]
    (if (item-charge-at-fire-point? ctx-id)
      (cfg-lerp :cost.tick.overload exp)
      0.0)
    0.0))

;; ---------------------------------------------------------------------------
;; Action handlers
;; ---------------------------------------------------------------------------

(defn- railgun-on-key-down
  "Coin-QTE path fires immediately; otherwise starts 20-tick iron-item charge."
  [ctx-id player-id _skill-id exp cost-ok? _hold-ticks _cost-stage player]
  (let [qte (read-coin-qte-status player-id)]
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
              (ctx-skill/replace-skill-state! ctx-id
                                     {:fired       true
                                      :mode        :performed
                                      :hit-count   normal-hit-count}))
            (log/debug "Railgun coin-QTE perform" player-id))
          (ctx-skill/replace-skill-state! ctx-id {:fired false :mode :coin-qte-no-resource})))

      (:has-window? qte)
      (do
        (mark-coin-judged! player-id (:coin-uuid qte))
        (ctx-skill/replace-skill-state! ctx-id {:fired false :mode :coin-qte-miss})
        (log/debug "Railgun coin-QTE miss" player-id (:progress qte)))

      (accepted-item-in-hand? player)
      (ctx-skill/replace-skill-state! ctx-id
                             {:fired        false
                              :mode         :item-charge
                              :charge-ticks (item-charge-ticks)
                              :hit-count    0})

      :else
      (ctx-skill/replace-skill-state! ctx-id {:fired false :mode :idle-no-trigger}))))

(defn- railgun-on-key-tick
  "Item-charge path: countdown; auto-fires when charge-ticks reaches zero."
  [ctx-id player-id _skill-id exp cost-ok? _hold-ticks _cost-stage player]
  (try
    (when-let [ctx-data (ctx-skill/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)]
        (when (= (:mode skill-state) :item-charge)
          (let [ticks-left (max 0 (int (or (:charge-ticks skill-state) 0)))]
            (if (<= ticks-left 1)
              (do
                (if (and (accepted-item-in-hand? player)
                         (consume-item-for-shot! player)
                         cost-ok?)
                  (let [{:keys [performed? reflection-hit? normal-hit-count hit-uuids]}
                        (perform-main-shot! player-id ctx-id exp)]
                    (when performed?
                      (skill-effects/add-skill-exp! player-id :railgun (railgun-exp-gain (pos? (long (or normal-hit-count 0))) reflection-hit?))
                      (when (and (pos? (long (or normal-hit-count 0)))
                                 (creeper-hit? (geom/world-id-of player-id) hit-uuids))
                        (ach-dispatcher/trigger-custom-event! player-id "electromaster.attack_creeper"))
                      (skill-effects/set-main-cooldown! player-id :railgun
                                                        (railgun-cooldown-ticks exp))
                      (ctx-skill/replace-skill-state! ctx-id
                                             {:fired     true
                                              :mode      :performed
                                              :hit-count normal-hit-count})))
                  (ctx-skill/replace-skill-state! ctx-id
                                         (assoc skill-state :fired false :mode :item-charge-failed)))
                (set-skill-state! ctx-id [:charge-ticks] 0))
              (set-skill-state! ctx-id [:charge-ticks] (dec ticks-left)))))))
    (catch Exception e
      (log/warn "railgun-on-key-tick error" (ex-message e)))))

(defn- railgun-on-key-up
  "Cancels an unfinished item charge. Cooldown is only applied on successful perform."
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (when-let [ctx (ctx-skill/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          mode        (:mode skill-state)]
      (when (and (= mode :item-charge) (not (:fired skill-state)))
        (ctx-skill/replace-skill-state! ctx-id
                               (assoc skill-state :mode :item-charge-cancelled :charge-ticks 0)))
      (when (:fired skill-state)
        (log/debug "Railgun completed")))))

(defn- railgun-on-key-abort
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (ctx-skill/clear-skill-state! ctx-id)
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
  :ctrl-id         :railgun
  :cp-consume-speed 1.0
  :overload-consume-speed 1.0
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
  (item-actions/register-item-action! (modid/namespaced-path "coin") :railgun-coin-throw)
  (item-actions/register-action-handler! :railgun-coin-throw register-coin-throw!)
  (item-actions/register-item-entity-spawn! "ac:coin" {:entity-id "entity_coin_throwing" :speed 0.0})
  (item-actions/register-item-entity-spawn! (modid/namespaced-path "coin") {:entity-id "entity_coin_throwing" :speed 0.0})
  nil)

