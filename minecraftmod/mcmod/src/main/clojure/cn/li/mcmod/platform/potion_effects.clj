(ns cn.li.mcmod.platform.potion-effects
  "Protocol for potion effect application."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IPotionEffects
  (apply-potion-effect! [this player-uuid effect-type duration amplifier])
  (remove-potion-effect! [this player-uuid effect-type])
  (has-potion-effect? [this player-uuid effect-type])
  (clear-all-effects! [this player-uuid]))

(defn install-potion-effects!
  [impl label]
  (when-let [fw-atom fw/*framework*] (swap! fw-atom assoc-in [:platform :potion-effects] impl)) nil)

(defn available? [] (boolean (get-in @fw/*framework* [:platform :potion-effects])))
(defn current [] (get-in @fw/*framework* [:platform :potion-effects]))
(defn call-with-runtime [rt f] (f rt))

(defn apply-potion-effect!* [player-uuid effect-type duration amplifier]
  (when-let [rt (get-in @fw/*framework* [:platform :potion-effects])]
    (apply-potion-effect! rt player-uuid effect-type duration amplifier)))
(defn remove-potion-effect!* [player-uuid effect-type]
  (when-let [rt (get-in @fw/*framework* [:platform :potion-effects])]
    (remove-potion-effect! rt player-uuid effect-type)))
(defn has-potion-effect?* [player-uuid effect-type]
  (when-let [rt (get-in @fw/*framework* [:platform :potion-effects])]
    (has-potion-effect? rt player-uuid effect-type)))
(defn clear-all-effects!* [player-uuid]
  (when-let [rt (get-in @fw/*framework* [:platform :potion-effects])]
    (clear-all-effects! rt player-uuid)))
