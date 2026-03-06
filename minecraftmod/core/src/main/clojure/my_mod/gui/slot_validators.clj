(ns my-mod.gui.slot-validators
  "Shared slot validation helpers for all platforms."
  (:require [my-mod.energy.operations :as energy]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]))

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
