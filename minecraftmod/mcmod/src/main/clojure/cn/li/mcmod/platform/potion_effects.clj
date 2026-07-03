(ns cn.li.mcmod.platform.potion-effects
  "Potion effect operations via Framework function map.

   Impl stored at [:platform :potion-effects]."
  (:require [cn.li.mcmod.framework :as fw]))

(def potion-effects-keys
  #{:apply-potion-effect! :remove-potion-effect! :has-potion-effect? :clear-all-effects!})

(defn install-potion-effects!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :potion-effects] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :potion-effects])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :potion-effects]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn apply-potion-effect!*  [player-uuid effect-type duration amplifier] (call :apply-potion-effect! player-uuid effect-type duration amplifier))
(defn remove-potion-effect!* [player-uuid effect-type]                  (call :remove-potion-effect! player-uuid effect-type))
(defn has-potion-effect?*    [player-uuid effect-type]                  (call :has-potion-effect? player-uuid effect-type))
(defn clear-all-effects!*    [player-uuid]                              (call :clear-all-effects! player-uuid))
