(ns cn.li.vfx.api
  "The public surface of vfx-core's NEW engine, for the client composition
   layer (ability-runtime's cn.li.ability.client-vfx-v2). Previously this
   facade delegated to cn.li.vfx.final-client but had zero real callers
   on either side (cn.li.ability.client-vfx, the old composition root,
   requires final-client directly, bypassing this file entirely) -- safe
   to repoint at the new engine with no old-engine call site affected.

   Sized from client-vfx-v2's own consumption of cn.li.vfx.runtime/cn.li.
   vfx.frame, not speculative coverage."
  (:require [cn.li.vfx.runtime :as runtime]
            [cn.li.vfx.frame :as frame]))

(defn create-runtime
  ([registry] (runtime/create-client-runtime registry))
  ([registry opts] (runtime/create-client-runtime registry opts)))
(defn dispatch-signal! [rt signal] (runtime/dispatch-signal! rt signal))
(defn tick! [rt dt] (runtime/client-tick! rt dt))
(defn sample-frame! [rt] (runtime/sample-client-frame! rt))
(defn frame-stage [rt frame-id stage] (runtime/frame-stage rt frame-id stage))
(defn latest-frame-stage [rt stage] (runtime/latest-frame-stage rt stage))
(defn release-frame! [rt frame-id] (runtime/release-frame! rt frame-id))
(defn clear-owner! [rt owner] (runtime/clear-owner! rt owner))
(defn clear-world! [rt world-id] (runtime/clear-world! rt world-id))
(defn reload-resources! [rt generation] (runtime/reload-resources! rt generation))
(defn resource-generation [rt] (runtime/resource-generation rt))
(defn registered-effects [rt] (runtime/registered-effects rt))
(defn instance-for-owner [rt effect-id owner] (runtime/instance-for-owner rt effect-id owner))
(def ->java-frame frame/->java-frame)
