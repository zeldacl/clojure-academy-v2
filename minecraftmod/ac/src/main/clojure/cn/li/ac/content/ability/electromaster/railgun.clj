(ns cn.li.ac.content.ability.electromaster.railgun
  "Railgun skill – coin-QTE + iron-item charge mechanic.

  Complex skill using the escape-hatch pattern: fn hooks for the custom
  coin-QTE / item-charge logic; :beam op (effect.beam) for the actual shot.

  No Minecraft imports."
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private accepted-item-ids
  #{"minecraft:iron_ingot" "minecraft:iron_block"})

(def ^:private coin-window-ms        1000)
(def ^:private coin-active-threshold 0.6)
(def ^:private coin-perform-threshold 0.7)
(def ^:private item-charge-ticks     20)
(def ^:private beam-radius           2.0)
(def ^:private beam-query-radius     30.0)
(def ^:private beam-step             0.9)
(def ^:private beam-max-distance     50.0)
(def ^:private beam-visual-distance  45.0)
(def ^:private reflect-distance      15.0)
(def ^:private reflect-damage        14.0)

;; ---------------------------------------------------------------------------
;; Player / item helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id) [:ability-data :skills :railgun :exp] 0.0)))

(defn- accepted-item-in-hand? [player]
  (when player
    (contains? accepted-item-ids (entity/player-get-main-hand-item-id player))))

(defn- consume-item-for-shot! [player]
  (if (or (nil? player) (entity/player-creative? player))
    true
    (entity/player-consume-main-hand-item! player 1)))

;; ---------------------------------------------------------------------------
;; Coin QTE window management
;; ---------------------------------------------------------------------------

(defn register-coin-throw!
  "Register a railgun coin throw window.
  Called from the platform item-action hook when a coin is used in ability mode."
  [player-id payload]
  (let [now-ms (long (or (:timestamp-ms payload) (System/currentTimeMillis)))]
    (ps/update-player-state! player-id assoc-in
                             [:runtime :railgun :coin-window]
                             {:start-ms  now-ms
                              :window-ms coin-window-ms
                              :source    :coin-item})
    (ps/mark-dirty! player-id)
    true))

(defn- clear-coin-window! [player-id]
  (ps/update-player-state! player-id update-in [:runtime :railgun] dissoc :coin-window)
  (ps/mark-dirty! player-id))

(defn- coin-progress [coin-window now-ms]
  (let [elapsed (- (long now-ms) (long (:start-ms coin-window)))
        window  (max 1 (long (:window-ms coin-window)))]
    (double (/ (max 0 elapsed) window))))

(defn- qte-status [p]
  {:has-window? true
   :progress    p
   :active?     (>= p coin-active-threshold)
   :perform?    (>= p coin-perform-threshold)})

(defn- consume-coin-qte-window! [player-id now-ms]
  (let [win (get-in (ps/get-player-state player-id) [:runtime :railgun :coin-window])]
    (if-not win
      {:has-window? false :active? false :perform? false :progress 0.0}
      (let [p (coin-progress win now-ms)]
        (clear-coin-window! player-id)
        (qte-status p)))))

(defn- peek-coin-qte-window [player-id now-ms]
  (let [win (get-in (ps/get-player-state player-id) [:runtime :railgun :coin-window])]
    (if-not win
      {:has-window? false :active? false :perform? false :progress 0.0}
      (qte-status (coin-progress win now-ms)))))

;; ---------------------------------------------------------------------------
;; Vec-reflection interaction
;; ---------------------------------------------------------------------------

(defn- toggle-active? [player-id skill-id]
  (some (fn [[_ ctx-data]]
          (and (= (:player-id ctx-data) player-id)
               (toggle/is-toggle-active? ctx-data skill-id)))
        (ctx/get-all-contexts)))

(defn- vec-reflection-can-reflect? [target-player-id incoming-damage]
  (when (toggle-active? target-player-id :vec-reflection)
    (when-let [state (ps/get-player-state target-player-id)]
      (let [exp        (get-in state [:ability-data :skills :vec-reflection :exp] 0.0)
            consumption (* (double incoming-damage) (bal/lerp 20.0 15.0 exp))
            current-cp (get-in state [:resource-data :cur-cp] 0.0)]
        (>= (double current-cp) (double consumption))))))

(defn- perform-reflection-shot!
  "Fire a secondary shot from the reflector player's perspective.
  Returns truthy if an entity was hit."
  [ctx-id reflector-player-id]
  (let [start-pos (geom/eye-pos reflector-player-id)
        world-id  (geom/world-id-of reflector-player-id)
        look-vec  (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* reflector-player-id))]
    (when look-vec
      (let [hit (raycast/raycast-entities raycast/*raycast*
                                          world-id
                                          (:x start-pos) (:y start-pos) (:z start-pos)
                                          (:dx look-vec) (:dy look-vec) (:dz look-vec)
                                          reflect-distance)
            actual-dist (if (= (:hit-type hit) :entity)
                          (double (or (:distance hit) reflect-distance))
                          reflect-distance)
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
                                              reflect-damage :magic)
          true)))))

;; ---------------------------------------------------------------------------
;; Main beam shot
;; ---------------------------------------------------------------------------

(defn- perform-main-shot!
  "Fires the railgun beam. Returns :beam-result map (or {:performed? false})."
  [player-id ctx-id exp]
  (let [world-id  (geom/world-id-of player-id)
        eye       (geom/eye-pos player-id)
        look-vec  (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (if-not look-vec
      {:performed? false}
      (let [damage   (bal/lerp 60.0 110.0 exp)
            result   (effect/run-op!
                       {:player-id       player-id
                        :ctx-id          ctx-id
                        :world-id        world-id
                        :eye-pos         eye
                        :look-dir        look-vec
                        :reflect-can-fn  (fn [uuid] (vec-reflection-can-reflect? uuid damage))
                        :reflect-shot-fn (fn [uuid] (perform-reflection-shot! ctx-id uuid))}
                       [:beam {:radius          beam-radius
                               :query-radius    beam-query-radius
                               :step            beam-step
                               :max-distance    beam-max-distance
                               :visual-distance beam-visual-distance
                               :damage          damage
                               :damage-type     :magic
                               :break-blocks?   true
                               :block-energy    (bal/lerp 900.0 2000.0 exp)
                               :fx-topic        :railgun/fx-shot}])]
        (or (:beam-result result) {:performed? false})))))

;; ---------------------------------------------------------------------------
;; Cost hooks (private – passed as fns in defskill!)
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
  (let [qte (peek-coin-qte-window player-id (System/currentTimeMillis))]
    (if (:perform? qte) (bal/lerp 200.0 450.0 (skill-exp player-id)) 0.0)))

(defn- down-cost-overload  [{:keys [player-id]}]
  (let [qte (peek-coin-qte-window player-id (System/currentTimeMillis))]
    (if (:perform? qte) (bal/lerp 180.0 120.0 (skill-exp player-id)) 0.0)))

(defn- tick-cost-cp        [{:keys [player-id ctx-id player]}]
  (if (item-charge-ready? ctx-id player)
    (bal/lerp 200.0 450.0 (skill-exp player-id))
    0.0))

(defn- tick-cost-overload  [{:keys [player-id ctx-id player]}]
  (if (item-charge-ready? ctx-id player)
    (bal/lerp 180.0 120.0 (skill-exp player-id))
    0.0))

;; ---------------------------------------------------------------------------
;; Action handlers
;; ---------------------------------------------------------------------------

(defn- railgun-on-key-down
  "Coin-QTE path fires immediately; otherwise starts 20-tick iron-item charge."
  [{:keys [player-id ctx-id player cost-ok?]}]
  (let [exp    (skill-exp player-id)
        now-ms (System/currentTimeMillis)
        qte    (consume-coin-qte-window! player-id now-ms)]
    (cond
      (:perform? qte)
      (if cost-ok?
        (let [{:keys [performed? reflection-hit? normal-hit-count]} (perform-main-shot! player-id ctx-id exp)]
          (when performed?
            (skill-effects/add-skill-exp! player-id :railgun (if reflection-hit? 0.01 0.005))
            (skill-effects/set-main-cooldown! player-id :railgun
                                              (int (Math/round (double (bal/lerp 300.0 160.0 exp)))))
            (ctx/update-context! ctx-id assoc :skill-state
                                 {:fired       true
                                  :mode        :performed
                                  :hit-count   normal-hit-count}))
          (log/debug "Railgun coin-QTE perform" player-id))
        (ctx/update-context! ctx-id assoc :skill-state {:fired false :mode :coin-qte-no-resource}))

      (:has-window? qte)
      (do
        (ctx/update-context! ctx-id assoc :skill-state {:fired false :mode :coin-qte-miss})
        (log/debug "Railgun coin-QTE miss" player-id (:progress qte)))

      (accepted-item-in-hand? player)
      (ctx/update-context! ctx-id assoc :skill-state
                           {:fired        false
                            :mode         :item-charge
                            :charge-ticks item-charge-ticks
                            :hit-count    0})

      :else
      (ctx/update-context! ctx-id assoc :skill-state {:fired false :mode :idle-no-trigger}))))

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
                        {:keys [performed? reflection-hit? normal-hit-count]}
                        (perform-main-shot! player-id ctx-id exp)]
                    (when performed?
                      (skill-effects/add-skill-exp! player-id :railgun (if reflection-hit? 0.01 0.005))
                      (skill-effects/set-main-cooldown! player-id :railgun
                                                        (int (Math/round (double (bal/lerp 300.0 160.0 exp)))))
                      (ctx/update-context! ctx-id assoc :skill-state
                                           {:fired     true
                                            :mode      :performed
                                            :hit-count normal-hit-count})))
                  (ctx/update-context! ctx-id assoc :skill-state
                                       (assoc skill-state :fired false :mode :item-charge-failed)))
                (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] 0))
              (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] (dec ticks-left)))))))
    (catch Exception e
      (log/warn "railgun-on-key-tick error" (ex-message e)))))

(defn- railgun-on-key-up
  "Cancels an unfinished item charge. Cooldown is only applied on successful perform."
  [{:keys [ctx-id]}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          mode        (:mode skill-state)]
      (when (and (= mode :item-charge) (not (:fired skill-state)))
        (ctx/update-context! ctx-id assoc :skill-state
                             (assoc skill-state :mode :item-charge-cancelled :charge-ticks 0)))
      (when (:fired skill-state)
        (log/debug "Railgun completed")))))

(defn- railgun-on-key-abort
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state)
  (log/debug "Railgun aborted"))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! railgun
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

;; ============================================================================
;; Self-register item actions at load time
;; ============================================================================

(item-actions/register-item-action! "ac:coin" :railgun-coin-throw)
(item-actions/register-item-action! "my_mod:coin" :railgun-coin-throw)

(item-actions/register-action-handler! :railgun-coin-throw register-coin-throw!)

