(ns cn.li.ac.gui.slot-validators
  "Shared slot validation helpers for all platforms."
  (:require [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]))

(def ^:private slot-validator-guard-lock
  (Object.))

(def ^:private ^:dynamic *default-slot-validators-installed?*
  false)

(defn energy-item-validator
  "Return true if stack is an energy item."
  [stack]
  (energy/is-energy-item-supported? stack))

(defn constraint-plate-validator
  "Return true if stack is a constraint plate item."
  [stack]
  (plate/is-constraint-plate? stack))

(defn matrix-core-validator
  "Return true if stack is a matrix core item."
  [stack]
  (core/is-mat-core? stack))

(defn output-slot-validator
  "Return false to block insertion into output slots."
  [_stack]
  false)

(defn register-default-slot-validators!
  "Install AC's standard typed slot validators into the shared slot registry."
  []
  (when-not (var-get #'*default-slot-validators-installed?*)
    (locking slot-validator-guard-lock
      (when-not (var-get #'*default-slot-validators-installed?*)
        (slot-registry/register-slot-validator! :energy energy-item-validator)
        (slot-registry/register-slot-validator! :plate constraint-plate-validator)
        (slot-registry/register-slot-validator! :core matrix-core-validator)
        (slot-registry/register-slot-validator! :output output-slot-validator)
        (alter-var-root #'*default-slot-validators-installed?* (constantly true)))))
  nil)
