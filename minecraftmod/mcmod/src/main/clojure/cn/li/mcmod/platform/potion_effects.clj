(ns cn.li.mcmod.platform.potion-effects
  "Protocol for potion effect application."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IPotionEffects
  (apply-potion-effect! [this player-uuid effect-type duration amplifier])
  (remove-potion-effect! [this player-uuid effect-type])
  (has-potion-effect? [this player-uuid effect-type])
  (clear-all-effects! [this player-uuid]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-potion-effects!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "potion-effects")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IPotionEffects
  [apply-potion-effect!* apply-potion-effect! player-uuid effect-type duration amplifier]
  [remove-potion-effect!* remove-potion-effect! player-uuid effect-type]
  [has-potion-effect?* has-potion-effect? player-uuid effect-type]
  [clear-all-effects!* clear-all-effects! player-uuid])
