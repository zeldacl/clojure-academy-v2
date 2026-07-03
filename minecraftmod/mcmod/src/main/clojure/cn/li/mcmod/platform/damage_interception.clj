(ns cn.li.mcmod.platform.damage-interception
  "Damage event interception via Framework function map.

   Impl stored at [:platform :damage-interception]."
  (:require [cn.li.mcmod.framework :as fw]))

(def damage-interception-keys
  #{:register-damage-handler! :unregister-damage-handler! :get-active-handlers})

(defn install-damage-interception!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :damage-interception] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :damage-interception])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :damage-interception]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn register-damage-handler!*   [handler-id handler-fn priority] (call :register-damage-handler! handler-id handler-fn priority))
(defn unregister-damage-handler!* [handler-id]                    (call :unregister-damage-handler! handler-id))
