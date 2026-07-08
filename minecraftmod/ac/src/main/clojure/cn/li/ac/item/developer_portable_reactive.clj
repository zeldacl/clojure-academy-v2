(ns cn.li.ac.item.developer-portable-reactive
  "Reactive Portable Developer — signal-driven portable dev screen.
   Replaces cgui-core widget tree + on-frame polling."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.client.platform-bridge :as bridge]))

(defn create-screen [container menu player]
  (let [r (rt/create-runtime)
        energy (sig/signal-d (double (or (some-> (:energy container) deref) 0.0)))
        progress (sig/signal-d 0.0)
        tier (sig/signal-o (or (some-> (:tier container) deref) :portable))]
    (rt/put-user-signal! r :energy energy)
    (rt/put-user-signal! r :progress progress)
    (rt/put-user-signal! r :tier tier)
    {:runtime r :container container :menu menu}))

(defn open! [screen]
  (bridge/open-reactive-screen! (:runtime screen) "Portable Developer"))
