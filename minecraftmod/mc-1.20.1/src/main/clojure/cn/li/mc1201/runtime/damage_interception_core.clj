(ns cn.li.mc1201.runtime.damage-interception-core
  "Shared Minecraft-side damage interception helpers (no loader API imports)."
  (:require [cn.li.mcmod.platform.damage-interception :as pdi]
            [cn.li.mcmod.hooks.core :as damage-hooks]))

(defn make-damage-interception
  []
  (reify pdi/IDamageInterception
    (register-damage-handler! [_ handler-id handler-fn priority]
      (damage-hooks/register-damage-handler! handler-id handler-fn priority))
    (unregister-damage-handler! [_ handler-id]
      (damage-hooks/unregister-damage-handler! handler-id))
    (get-active-handlers [_]
      (damage-hooks/get-active-damage-handlers))))

(defn install-damage-interception!
  []
  (alter-var-root #'pdi/*damage-interception*
                  (constantly (make-damage-interception))))

(defn should-allow-attack?
  [player-id attacker-id original-damage damage-source]
  (not (damage-hooks/should-cancel-attack-interception?
         player-id attacker-id original-damage damage-source)))

(defn process-damage
  [player-id attacker-id original-damage damage-source]
  (damage-hooks/process-damage-interception
    player-id attacker-id original-damage damage-source))
