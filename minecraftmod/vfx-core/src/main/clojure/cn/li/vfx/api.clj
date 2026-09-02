(ns cn.li.vfx.api
  "The public surface of vfx-core, for the client composition layer
   (ability-runtime's cn.li.ability.client-vfx, shared by every content
   module -- moved out of ac in P4.5) and, eventually, catalog assembly
   (system-compiler, vocabulary).

   Sized from ac's actual consumption of cn.li.vfx.final-client's 15
   functions, not speculative coverage. cn.li.vfx.network has zero real
   consumers anywhere in the repo today (ac's server_hooks.clj requires it
   but never calls it) and is deliberately left off this facade until a
   real caller needs it."
  (:require [cn.li.vfx.final-client :as client]))

(defn create-runtime [opts] (client/create-runtime opts))
(defn register-effect!
  ([runtime descriptor] (client/register-effect! runtime descriptor))
  ([runtime descriptor opts] (client/register-effect! runtime descriptor opts)))
(defn freeze-registry! [runtime] (client/freeze-registry! runtime))
(defn dispatch-signal! [runtime signal] (client/dispatch-signal! runtime signal))
(defn tick! [runtime context] (client/tick! runtime context))
(defn sample-frame! [runtime context] (client/sample-frame! runtime context))
(defn release-frame! [runtime frame-id] (client/release-frame! runtime frame-id))
(defn frame-stage [runtime frame-id stage] (client/frame-stage runtime frame-id stage))
(defn latest-frame-stage [runtime stage] (client/latest-frame-stage runtime stage))
(defn clear-owner! [runtime owner] (client/clear-owner! runtime owner))
(defn clear-world! [runtime world-id] (client/clear-world! runtime world-id))
(defn reload-resources! [runtime generation] (client/reload-resources! runtime generation))
(defn resource-generation [runtime] (client/resource-generation runtime))
(defn registered-effects [runtime] (client/registered-effects runtime))
(defn instance-for-owner [runtime effect-id owner] (client/instance-for-owner runtime effect-id owner))
