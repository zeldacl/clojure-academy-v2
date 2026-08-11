(ns cn.li.test-support.runtime-facades
  "Install the same neutral runtime facades used by platform bootstrap for
  isolated Clojure tests.

  The values intentionally remain Vars rather than dereferenced functions.
  Existing tests use `with-redefs` against the legacy runtime namespaces; Var
  delegation keeps those redefinitions visible to the AOT-facing facades."
  (:require [cn.li.mcmod.runtime.hooks-provider :as hooks-provider]
            [cn.li.mcmod.content.registry :as content-registry]
            [cn.li.mcmod.hooks.messages :as messages]
            [cn.li.mcmod.hooks.tutorial-events :as tutorial-events]
            [cn.li.mcmod.integration.energy-conversion :as energy-conversion]
            [cn.li.mcmod.integration.energy-hooks :as energy-hooks]
            [cn.li.mcmod.integration.runtime-hooks :as legacy-integration]
            [cn.li.mcmod.network.binary-codec :as binary-codec]
            [cn.li.mcmod.network.client :as client]
            [cn.li.mcmod.network.server :as server]
            [cn.li.platform.neutral.client-network :as client-network]
            [cn.li.platform.neutral.hooks :as hooks]
            [cn.li.platform.neutral.integration-runtime :as integration-runtime]
            [cn.li.platform.neutral.network-runtime :as network-runtime]))

(defn install!
  "Install complete, test-safe provider maps for neutral AOT facades."
  []
  (hooks/install! (hooks-provider/runtime-provider nil))
  (client-network/install!
    {:register-request-transport! #'client/register-request-transport!
     :send-to-server #'client/send-to-server
     :clear-client-session-state! #'client/clear-client-session-state!
     :handle-push #'client/handle-push
     :handle-response #'client/handle-response})
  (network-runtime/install!
    {:encode #'binary-codec/encode
     :decode #'binary-codec/decode
     :list-descriptors #'content-registry/list-descriptors
     :handle-request #'server/handle-request})
  (integration-runtime/install!
    {:content-to-fe #'energy-conversion/content-to-fe
     :fe-to-content #'energy-conversion/fe-to-content
     :validate-conversion-rate #'energy-conversion/validate-conversion-rate
     :forge-energy-conversion-rate #'energy-hooks/forge-energy-conversion-rate
     :ic2-energy-conversion-rate #'energy-hooks/ic2-energy-conversion-rate
     :jei-get-all-categories #'legacy-integration/jei-get-all-categories
     :jei-get-recipes #'legacy-integration/jei-get-recipes
     :jei-format-recipe #'legacy-integration/jei-format-recipe
     :get-jei-nbt-subtype-item-ids #'legacy-integration/get-jei-nbt-subtype-item-ids
     :msg-id #'messages/msg-id
     :on-item-event! #'tutorial-events/on-item-event!
     :process-pending-activations! #'tutorial-events/process-pending-activations!
     :register-tutorial-activated-hook! #'tutorial-events/register-tutorial-activated-hook!})
  nil)
