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
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Mining tick logic (shared)
;; ---------------------------------------------------------------------------

(defn mining-ray-down!
  "Initialize mining ray context state."
  [skill-id {:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state
                         {:target-x  nil
                          :target-y  nil
                          :target-z  nil
                          :countdown 0.0})))

(defn mining-ray-tick!
  "Tick handler for mining ray.
  cfg: {:range double :break-speed double :skill-id keyword :lucky? boolean}"
  [cfg {:keys [player-id ctx-id]}]
  (try
    (let [{:keys [range break-speed skill-id lucky?]} cfg
          ctx-data  (ctx/get-context ctx-id)
          world-id  (geom/world-id-of player-id)
          eye       (geom/eye-pos player-id)
          look-vec  (when raycast/*raycast*
                      (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when (and look-vec bm/*block-manipulation*)
        (let [hit (raycast/raycast-blocks
                    raycast/*raycast*
                    world-id
                    (:x eye) (:y eye) (:z eye)
                    (:x look-vec) (:y look-vec) (:z look-vec)
                    (double range))]
          (if (nil? hit)
            ;; No block in range - reset target
            (ctx/update-context! ctx-id assoc :skill-state
                                 {:target-x nil :target-y nil :target-z nil :countdown 0.0})
            (let [hx (int (:x hit)) hy (int (:y hit)) hz (int (:z hit))
                  prev-x (get-in ctx-data [:skill-state :target-x])
                  prev-y (get-in ctx-data [:skill-state :target-y])
                  prev-z (get-in ctx-data [:skill-state :target-z])
                  same-target? (and (= hx prev-x) (= hy prev-y) (= hz prev-z))
                  hardness (double (or (bm/get-block-hardness bm/*block-manipulation*
                                                               world-id hx hy hz)
                                       1.0))
                  ;; Countdown decreases by break-speed each tick; harder blocks take longer
                  countdown-delta (/ (double break-speed) (max 0.1 hardness))
                  prev-countdown (if same-target?
                                   (double (or (get-in ctx-data [:skill-state :countdown]) 0.0))
                                   0.0)
                  new-countdown (+ prev-countdown countdown-delta)]
              ;; Send FX progress to client
              (ctx/ctx-send-to-client! ctx-id :mine-ray/fx-progress
                                       {:x hx :y hy :z hz
                                        :progress (min 1.0 new-countdown)})
              (if (>= new-countdown 1.0)
                ;; Block ready to break
                (when (bm/can-break-block? bm/*block-manipulation* player-id world-id hx hy hz)
                  (bm/break-block! bm/*block-manipulation* player-id world-id hx hy hz true)
                  (skill-effects/add-skill-exp! player-id skill-id 0.001)
                  (ctx/update-context! ctx-id assoc :skill-state
                                       {:target-x nil :target-y nil :target-z nil :countdown 0.0}))
                ;; Still counting down
                (ctx/update-context! ctx-id assoc :skill-state
                                     {:target-x  hx
                                      :target-y  hy
                                      :target-z  hz
                                      :countdown new-countdown})))))))
    (catch Exception e
      (log/warn "MiningRay tick! failed:" (ex-message e)))))

(defn mining-ray-up!
  "Key-up: reset mining state."
  [_cfg {:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state
                       {:target-x nil :target-y nil :target-z nil :countdown 0.0}))

(defn mining-ray-abort!
  "Abort: reset mining state."
  [_cfg {:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state
                       {:target-x nil :target-y nil :target-z nil :countdown 0.0}))
