(ns cn.li.mc1201.lifecycle.orchestrator
  "Shared lifecycle runner used by platform modules.

  Platforms provide ordered phase descriptors while this namespace centralizes
  execution order logging and failure framing."
  (:require [cn.li.mcmod.util.log :as log]))

(defn run-lifecycle!
  [{:keys [label phases]
    :or {label "platform" phases []}}]
  (log/info "[LIFECYCLE] begin" {:label label :phase-count (count phases)})
  (doseq [{:keys [id desc fn]} phases]
    (log/info "[LIFECYCLE] phase start" {:label label :phase id :desc desc})
    (fn)
    (log/info "[LIFECYCLE] phase done" {:label label :phase id}))
  (log/info "[LIFECYCLE] complete" {:label label})
  nil)
