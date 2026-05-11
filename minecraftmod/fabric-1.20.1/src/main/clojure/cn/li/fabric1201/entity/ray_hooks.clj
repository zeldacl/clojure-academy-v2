(ns cn.li.fabric1201.entity.ray-hooks
  "Fabric thin wrapper delegating scripted ray hook registration to shared mc1201 implementation."
  (:require [cn.li.mc1201.entity.ray-hooks :as shared]))

(defn register-all-ray-hooks!
  []
  (shared/register-all-ray-hooks!))
