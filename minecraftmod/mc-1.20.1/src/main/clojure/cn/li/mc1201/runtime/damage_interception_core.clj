(ns cn.li.mc1201.runtime.damage-interception-core
  "Shared Minecraft-side damage interception helpers (no loader API imports)."
  (:require [cn.li.mcmod.platform.damage-interception :as pdi]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]))

(defn make-damage-interception
  []
  (reify pdi/IDamageInterception
    (register-damage-handler! [_ handler-id handler-fn priority]
      (power-runtime/register-damage-handler! handler-id handler-fn priority))
    (unregister-damage-handler! [_ handler-id]
      (power-runtime/unregister-damage-handler! handler-id))
    (get-active-handlers [_]
      (power-runtime/get-active-damage-handlers))))

(defn install-damage-interception!
  []
  (alter-var-root #'pdi/*damage-interception*
                  (constantly (make-damage-interception))))

(defn should-allow-attack?
  [player-id attacker-id original-damage damage-source]
  (not (power-runtime/should-cancel-attack-interception?
         player-id attacker-id original-damage damage-source)))

(defn process-damage
  [player-id attacker-id original-damage damage-source]
  (power-runtime/process-damage-interception
    player-id attacker-id original-damage damage-source))
