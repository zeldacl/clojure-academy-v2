(ns cn.li.mcmod.platform.ops-integration
  "Integration hooks and event handling abstractions.
  
  This namespace consolidates protocols and hooks for platform-specific integrations:
  - Event posting (*fire-event-fn* for Forge event bus)
  - Platform version dispatch (*platform-version* selector)
  - Command hook registration
  - JEI / CraftTweaker integration hooks
  - Energy system conversion rates"
  (:require
    [cn.li.mcmod.platform.events :as events]
    [cn.li.mcmod.platform.dispatch :as dispatch]
    [cn.li.mcmod.platform.command-runtime :as command-runtime]
    [cn.li.mcmod.platform.integration-runtime :as integration-runtime]
    [cn.li.mcmod.platform.energy-integration :as energy-integration]))

(def fire-event! events/fire-event!)
(def register-command-hooks! command-runtime/register-command-hooks!)
(def init-commands! command-runtime/init-commands!)
(def register-integration-hooks! integration-runtime/register-integration-hooks!)
(def forge-energy-conversion-rate energy-integration/forge-energy-conversion-rate)
(def ic2-energy-conversion-rate energy-integration/ic2-energy-conversion-rate)
(def register-energy-integration-hooks! energy-integration/register-energy-integration-hooks!)
