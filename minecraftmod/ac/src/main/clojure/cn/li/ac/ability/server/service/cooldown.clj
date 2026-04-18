(ns cn.li.ac.ability.server.service.cooldown
  "Pure wrappers over cn.li.ac.ability.model.cooldown."
  (:require [cn.li.ac.ability.model.cooldown :as cdata]
            [cn.li.mcmod.util.log :as log]))

(defn set-cooldown
  "Set cooldown (ctrl-id, sub-id) to max(existing, ticks)."
  [cd-data ctrl-id sub-id ticks]
  (cdata/set-cooldown cd-data ctrl-id sub-id ticks))

(defn set-main-cooldown
  "Convenience: set main (sub-id=:main) cooldown."
  [cd-data ctrl-id ticks]
  (cdata/set-cooldown cd-data ctrl-id :main ticks))

(defn in-cooldown?
  [cd-data ctrl-id sub-id]
  (cdata/in-cooldown? cd-data ctrl-id sub-id))

(defn in-main-cooldown?
  [cd-data ctrl-id]
  (cdata/in-cooldown? cd-data ctrl-id :main))

(defn tick-cooldowns
  "Decrement all cooldowns. To be called once per server tick."
  [cd-data]
  (cdata/tick-cooldowns cd-data))

(defn get-remaining
  [cd-data ctrl-id sub-id]
  (cdata/get-remaining cd-data ctrl-id sub-id))
