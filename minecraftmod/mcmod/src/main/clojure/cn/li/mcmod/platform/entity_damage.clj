(ns cn.li.mcmod.platform.entity-damage
  "Protocol for entity damage application."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IEntityDamage
  (apply-direct-damage! [this world-id entity-uuid damage source-type])
  (apply-aoe-damage! [this world-id x y z radius damage source-type falloff?])
  (apply-reflection-damage! [this world-id entity-uuid damage source-type reflection-count max-reflections]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-entity-damage!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "entity-damage")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IEntityDamage
  [apply-direct-damage!* apply-direct-damage! world-id entity-uuid damage source-type]
  [apply-aoe-damage!* apply-aoe-damage! world-id x y z radius damage source-type falloff?]
  [apply-reflection-damage!* apply-reflection-damage! world-id entity-uuid damage source-type reflection-count max-reflections])
