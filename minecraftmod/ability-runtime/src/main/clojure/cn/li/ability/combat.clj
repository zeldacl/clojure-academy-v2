(ns cn.li.ability.combat
  "Mod-level composition for Combat Core delayed work.

   The queue is owned by ability-runtime and is instance-local. Combat Core
   contributes only the neutral beam settlement executor; AC/BC/CC consume
   this same boundary and provide their own lifecycle/VFX finalization."
  (:require [cn.li.ability.continuation :as continuation]
            [cn.li.combat.beam-settlement :as beam]))

(defn create-runtime
  "Create one logical server combat continuation runtime.
   `execute-result!` receives [owner neutral-result] and is the content
   composition boundary for VFX/result publication."
  [{:keys [execute-result!]}]
  (continuation/create
   {:execute! (fn [owner payload]
                (let [result (beam/settle! payload)]
                  (when (ifn? execute-result!)
                    (execute-result! owner result))
                  result))}))

(defn schedule!
  [runtime request]
  (beam/schedule-action!
   #(continuation/schedule! runtime %)
   request))

(defn tick-owner!
  [runtime owner]
  (continuation/tick-owner! runtime owner))

(defn cancel-owner!
  [runtime owner]
  (continuation/cancel-owner! runtime owner))

(defn cancel-all!
  [runtime]
  (continuation/cancel-all! runtime))

(defonce ^:private runtime* (atom nil))

(defn install-runtime!
  "Install the one mod-level combat continuation runtime at composition time.
   All content modules share it; owner keys keep their queues isolated."
  [runtime]
  (when-not (map? runtime)
    (throw (ex-info "combat continuation runtime must be a map" {})))
  (reset! runtime* runtime)
  runtime)

(defn runtime []
  (or @runtime*
      (throw (ex-info "combat continuation runtime is not installed" {}))))

(defn schedule-installed!
  [request]
  (schedule! (runtime) request))

(defn tick-installed-owner! [owner]\n  (when-let [rt @runtime*]\n    (tick-owner! rt owner)))

(defn cancel-installed-owner! [owner]\n  (when-let [rt @runtime*]\n    (cancel-owner! rt owner)))

(defn cancel-installed-all! []\n  (when-let [rt @runtime*]\n    (cancel-all! rt)))
