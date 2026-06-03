(ns cn.li.mcmod.platform.damage-interception
  "Protocol for intercepting and modifying damage events."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IDamageInterception
  (register-damage-handler! [this handler-id handler-fn priority])
  (unregister-damage-handler! [this handler-id])
  (get-active-handlers [this]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-damage-interception!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "damage-interception")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IDamageInterception
  [register-damage-handler!* register-damage-handler! handler-id handler-fn priority]
  [unregister-damage-handler!* unregister-damage-handler! handler-id]
  [get-active-handlers* get-active-handlers])
