(ns cn.li.ability.combat
  "Mod-level composition for Combat Core delayed work.

   The queue is owned by ability-runtime and is instance-local. Combat Core
   contributes only the neutral beam settlement executor; AC/BC/CC consume
   this same boundary and provide their own lifecycle/VFX finalization."
  (:require [cn.li.ability.continuation :as continuation]
            [cn.li.combat.beam-settlement :as beam]))

(defn create-runtime
  "Create one logical server combat continuation runtime.

   `execute-result!` is {content-id -> (fn [owner neutral-result])}, the
   content composition boundary for VFX/result publication -- one entry per
   content module, not one shared closure. `content-id-for` is
   (fn [ability-id] -> content-id), used to route a settled continuation
   (which only carries :ability-id -- see final_engine.clj's :action branch
   and final_runtime.clj's create-from-capabilities, which thread it through
   combat-core's command/apply boundary the same way :owner/:world-id
   already were) to the right tenant's finalizer. A payload with no
   :ability-id (nothing upstream supplied one) or an ability-id
   content-id-for can't place still settles -- beam/settle! always runs --
   it just has no one to notify."
  [{:keys [execute-result! content-id-for]}]
  (continuation/create
   {:execute! (fn [owner payload]
                (let [result (beam/settle! payload)
                      content-id (when (ifn? content-id-for)
                                   (content-id-for (:ability-id payload)))
                      finalize! (get execute-result! content-id)]
                  (when (ifn? finalize!)
                    (finalize! owner result))
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

(defn tick-installed-owner! [owner]
  (when-let [rt @runtime*]
    (tick-owner! rt owner)))

(defn cancel-installed-owner! [owner]
  (when-let [rt @runtime*]
    (cancel-owner! rt owner)))

(defn cancel-installed-all! []
  (when-let [rt @runtime*]
    (cancel-all! rt)))
