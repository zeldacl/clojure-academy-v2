(ns cn.li.mcmod.platform.entity-motion
  "Protocol for manipulating generic entity motion."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IEntityMotion
  (set-velocity! [this world-id entity-uuid x y z])
  (add-velocity! [this world-id entity-uuid x y z])
  (discard-entity! [this world-id entity-uuid])
  (get-velocity [this world-id entity-uuid]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-entity-motion!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "entity-motion")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IEntityMotion
  [set-velocity!* set-velocity! world-id entity-uuid x y z]
  [add-velocity!* add-velocity! world-id entity-uuid x y z]
  [discard-entity!* discard-entity! world-id entity-uuid]
  [get-velocity* get-velocity world-id entity-uuid])
