(ns cn.li.ac.ability.spi-content
  "SPI facade for declaring ability content providers.

  External modules can use this namespace to contribute ability skill and FX
  namespaces without reaching into the internal discovery implementation."
  (:require [cn.li.ac.ability.discovery :as discovery]))

(defn declare-ability-provider!
  "Register or replace an ability content provider.

  Provider map keys:
  - :id keyword
  - :priority integer (lower loads earlier, default 100)
  - :skill-namespaces [symbol ...]
  - :fx-namespaces [symbol ...]"
  [provider]
  (discovery/register-provider! provider))

(defn retract-ability-provider!
  [provider-id]
  (discovery/unregister-provider! provider-id))

(defn list-ability-providers
  []
  (discovery/registered-providers))

(defn list-skill-namespaces
  []
  (discovery/discovered-skill-namespaces))

(defn list-fx-namespaces
  []
  (discovery/discovered-fx-namespaces))

(defn declare-skill-namespace!
  ([provider-id ns-sym]
   (declare-skill-namespace! provider-id ns-sym 100))
  ([provider-id ns-sym priority]
   (discovery/register-skill-namespace! provider-id ns-sym priority)))

(defn declare-fx-namespace!
  ([provider-id ns-sym]
   (declare-fx-namespace! provider-id ns-sym 100))
  ([provider-id ns-sym priority]
   (discovery/register-fx-namespace! provider-id ns-sym priority)))
