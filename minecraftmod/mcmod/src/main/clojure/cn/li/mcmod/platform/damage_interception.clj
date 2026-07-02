(ns cn.li.mcmod.platform.damage-interception
  "Protocol for intercepting and modifying damage events."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IDamageInterception
  (register-damage-handler! [this handler-id handler-fn priority])
  (unregister-damage-handler! [this handler-id])
  (get-active-handlers [this]))

(defn install-damage-interception!
  [impl label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :damage-interception] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :damage-interception])))
(defn current [] (get-in @(fw/fw-atom) [:platform :damage-interception]))
(defn call-with-runtime [rt f] (f rt))

(defn register-damage-handler!* [handler-id handler-fn priority]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :damage-interception])]
    (register-damage-handler! rt handler-id handler-fn priority)))
(defn unregister-damage-handler!* [handler-id]
  (when-let [rt (get-in @(fw/fw-atom) [:platform :damage-interception])]
    (unregister-damage-handler! rt handler-id)))
