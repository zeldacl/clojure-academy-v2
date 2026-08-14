(ns cn.li.mc1201.client.session-cleanup
  "Session cleanup -- installs Presentation world walk-speed hook, re-exports core."
  (:require [cn.li.mcbase.client.session-cleanup-core :as shared]
            [cn.li.mc1201.client.effects.presentation-world :as presentation-world]))

(shared/install-session-cleanup-hooks!
  {:clear-walk-speed! presentation-world/clear-owner-walk-speed!})

(def create-session-cleanup-runtime shared/create-session-cleanup-runtime)
(def call-with-session-cleanup-runtime shared/call-with-session-cleanup-runtime)
(def cleanup-state-snapshot shared/cleanup-state-snapshot)
(def reset-cleanup-state-for-test! shared/reset-cleanup-state-for-test!)
(def clear-owner-state! shared/clear-owner-state!)
(def tick-connection-change! shared/tick-connection-change!)
