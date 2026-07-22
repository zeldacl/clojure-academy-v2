(ns cn.li.ac.content.ability.meltdowner.mine-rays-base
  "Shared hold-channel mining ray base logic for Basic/Expert/Luck variants.

  Each variant provides parameterized configuration; this namespace
  provides the actual tick/up/down implementations.

  Mining ray mechanic:
  - Raycast in look direction each tick
  - If block found, start hardness countdown (speed param)
  - When countdown reaches 0, break block
  - Player must keep aiming at same block to continue countdown
  - Mine-ray-luck grants fortune-style extra drops

  No Minecraft imports."
  (:require            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
                        [cn.li.ac.ability.effects.raycast :as raycast]
            [cn.li.ac.ability.effects.block :as bm]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Mining tick logic (shared)
;; ---------------------------------------------------------------------------

(defn- empty-skill-state
  []
  {:target-x nil :target-y nil :target-z nil :countdown 0.0})

(defn mining-ray-down!
  "Initialize mining ray context state."
  [_skill-id ctx-id _player-id _callback-skill-id _exp cost-ok? _hold-ticks _cost-stage _player-ref]
  (when cost-ok?
    (ctx-skill/replace-skill-state! ctx-id (empty-skill-state))))

(defn mining-ray-tick!
  "Tick handler for mining ray.
  cfg: {:range double :break-speed double :skill-id keyword :fortune-level int :exp-block double}"
  [cfg ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (try
    (let [{:keys [range break-speed skill-id fortune-level exp-block]} cfg
          ctx-data  (ctx-skill/get-context ctx-id)
          world-id  (geom/world-id-of player-id)
          eye       (geom/eye-pos player-id)
          look-vec  (when (raycast/available?)
                      (raycast/player-look-vector player-id))]
      (if (and look-vec (bm/available?))
        (let [hit (raycast/raycast-blocks
                    world-id
                    (:x eye) (:y eye) (:z eye)
                    (:x look-vec) (:y look-vec) (:z look-vec)
                    (double range))]
          (if (nil? hit)
            (ctx-skill/replace-skill-state! ctx-id (empty-skill-state))
            (let [hx (int (:x hit)) hy (int (:y hit)) hz (int (:z hit))
                  prev-x (get-in ctx-data [:skill-state :target-x])
                  prev-y (get-in ctx-data [:skill-state :target-y])
                  prev-z (get-in ctx-data [:skill-state :target-z])
                  same-target? (and (= hx prev-x) (= hy prev-y) (= hz prev-z))
                  hardness (double (or (bm/get-block-hardness
                                                               world-id hx hy hz)
                                       1.0))
                  countdown-delta (/ (double break-speed) (max 0.1 hardness))
                  prev-countdown (if same-target?
                                   (double (or (get-in ctx-data [:skill-state :countdown]) 0.0))
                                   0.0)
                  new-countdown (+ prev-countdown countdown-delta)]
              (fx/send! ctx-id {:topic :mine-ray/fx-progress} nil
                        {:x hx :y hy :z hz
                         :progress (min 1.0 new-countdown)})
              (if (>= new-countdown 1.0)
                (when (bm/can-break-block? player-id world-id hx hy hz)
                  (if (pos? (long (or fortune-level 0)))
                    (bm/break-block! player-id world-id hx hy hz true fortune-level)
                    (bm/break-block! player-id world-id hx hy hz true))
                  (skill-effects/add-skill-exp! player-id skill-id (double (or exp-block 0.001)))
                  (ctx-skill/replace-skill-state! ctx-id (empty-skill-state)))
                (ctx-skill/replace-skill-state! ctx-id
                                       {:target-x hx
                                        :target-y hy
                                        :target-z hz
                                        :countdown new-countdown})))))
        (ctx-skill/replace-skill-state! ctx-id (empty-skill-state))))
    (catch Exception e
      (log/warn "MiningRay tick! failed:" (ex-message e)))))

(defn mining-ray-up!
  "Key-up: reset mining state."
  [_cfg ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (ctx-skill/replace-skill-state! ctx-id (empty-skill-state)))

(defn mining-ray-abort!
  "Abort: reset mining state."
  [_cfg ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (ctx-skill/replace-skill-state! ctx-id (empty-skill-state)))
