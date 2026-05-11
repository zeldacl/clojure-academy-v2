(ns cn.li.fabric1201.entity.effect-hooks
  "Fabric thin wrapper delegating scripted effect hook registration to shared mc1201 implementation."
  (:require [cn.li.mc1201.entity.effect-hooks :as shared]))

(defn register-all-effect-hooks!
  []
  (shared/register-all-effect-hooks!))
