(ns cn.li.mcmod.platform.ability-lifecycle
  "Platform-neutral bridge for ability runtime lifecycle hooks.

  forge/fabric adapters invoke these functions from platform events.
  ac registers concrete handlers during core initialization.")

(def ^:private noop
  (fn [& _] nil))

(defonce ^:private runtime-hooks
  (atom {:on-player-login! noop
         :on-player-logout! noop
         :on-player-clone! noop
         :on-player-death! noop
         :on-player-tick! noop
         :init-damage-handlers! noop
         :list-player-uuids (fn [] [])
         :build-sync-payload (fn [_] nil)
         :mark-player-clean! noop
         :get-player-state (fn [_] nil)
         :set-player-state! noop
         :get-or-create-player-state! (fn [_] nil)
         :fresh-player-state (fn [] nil)
         :register-network-handlers! noop
         :register-context-route-fns! noop
         :register-context-send-fns! noop
         :get-context-player-uuid (fn [_] nil)
         :register-damage-handler! (fn [_ _ _] false)
         :unregister-damage-handler! (fn [_] false)
         :get-active-damage-handlers (fn [] [])
         :process-damage-interception (fn [_ _ damage _] damage)
         :resolve-item-use-action (fn [_] nil)
         :on-ability-item-action! noop
         :compute-aoe-damage (fn [_ _ _ damage _] damage)
         :select-reflection-target (fn [_ _ _ _] nil)
         :compute-reflected-damage identity
         :client-get-skill-by-controllable (fn [_ _] nil)
         :client-new-context (fn [_ _] nil)
         :client-register-context! noop
         :client-get-context (fn [_] nil)
         :client-terminate-context! noop
         :client-transition-to-alive! noop
         :client-send-context-local! noop
         :client-update-ability-data! noop
         :client-update-resource-data! noop
         :client-update-cooldown-data! noop
         :client-update-preset-data! noop
         :client-build-hud-render-data (fn [_ _ _ _] nil)
         :client-req-learn-skill! noop
         :client-req-level-up! noop
         :client-req-set-activated! noop
         :client-req-set-preset-slot! noop
         :client-req-switch-preset! noop
         :client-open-skill-tree-screen! (fn [_ _] nil)
         :client-build-skill-tree-render-data (fn [] nil)
         :client-handle-skill-tree-hover! noop
         :client-handle-skill-tree-click! (fn [_ _] false)
         :client-close-skill-tree-screen! noop
         :client-open-preset-editor-screen! (fn [_] nil)
         :client-build-preset-editor-render-data (fn [] nil)
         :client-handle-preset-editor-click! (fn [_ _] false)
         :client-close-preset-editor-screen! noop
         :client-poll-particle-effects (fn [] [])
         :client-poll-sound-effects (fn [] [])
         :client-tick-keys! noop
         :client-trigger-mode-switch! noop}))

(defn register-ability-runtime-hooks!
  "Register/replace ability runtime hook fns."
  [hooks]
  (swap! runtime-hooks merge hooks)
  nil)

(defn on-player-login!
  [player-uuid]
  ((:on-player-login! @runtime-hooks) player-uuid))

(defn on-player-logout!
  [player-uuid]
  ((:on-player-logout! @runtime-hooks) player-uuid))

(defn on-player-clone!
  [old-player-uuid new-player-uuid]
  ((:on-player-clone! @runtime-hooks) old-player-uuid new-player-uuid))

(defn on-player-death!
  [player-uuid]
  ((:on-player-death! @runtime-hooks) player-uuid))

(defn on-player-tick!
  [player-uuid]
  ((:on-player-tick! @runtime-hooks) player-uuid))

(defn init-damage-handlers!
  []
  ((:init-damage-handlers! @runtime-hooks)))

(defn list-player-uuids
  []
  ((:list-player-uuids @runtime-hooks)))

(defn build-sync-payload
  [player-uuid]
  ((:build-sync-payload @runtime-hooks) player-uuid))

(defn mark-player-clean!
  [player-uuid]
  ((:mark-player-clean! @runtime-hooks) player-uuid))

(defn get-player-state
  [player-uuid]
  ((:get-player-state @runtime-hooks) player-uuid))

(defn set-player-state!
  [player-uuid state]
  ((:set-player-state! @runtime-hooks) player-uuid state))

(defn get-or-create-player-state!
  [player-uuid]
  ((:get-or-create-player-state! @runtime-hooks) player-uuid))

(defn fresh-player-state
  []
  ((:fresh-player-state @runtime-hooks)))

(defn register-network-handlers!
  []
  ((:register-network-handlers! @runtime-hooks)))

(defn register-context-route-fns!
  [fns-map]
  ((:register-context-route-fns! @runtime-hooks) fns-map))

(defn register-context-send-fns!
  [fns-map]
  ((:register-context-send-fns! @runtime-hooks) fns-map))

(defn get-context-player-uuid
  [ctx-id]
  ((:get-context-player-uuid @runtime-hooks) ctx-id))

(defn register-damage-handler!
  [handler-id handler-fn priority]
  ((:register-damage-handler! @runtime-hooks) handler-id handler-fn priority))

(defn unregister-damage-handler!
  [handler-id]
  ((:unregister-damage-handler! @runtime-hooks) handler-id))

(defn get-active-damage-handlers
  []
  ((:get-active-damage-handlers @runtime-hooks)))

(defn process-damage-interception
  [player-id attacker-id damage damage-source]
  ((:process-damage-interception @runtime-hooks) player-id attacker-id damage damage-source))

(defn resolve-item-use-action
  [item-id]
  ((:resolve-item-use-action @runtime-hooks) item-id))

(defn on-ability-item-action!
  [action player-uuid payload]
  ((:on-ability-item-action! @runtime-hooks) action player-uuid payload))

(defn compute-aoe-damage
  [origin-pos target-pos radius damage falloff?]
  ((:compute-aoe-damage @runtime-hooks) origin-pos target-pos radius damage falloff?))

(defn select-reflection-target
  [current-entity-uuid current-pos candidates max-radius]
  ((:select-reflection-target @runtime-hooks) current-entity-uuid current-pos candidates max-radius))

(defn compute-reflected-damage
  [current-damage]
  ((:compute-reflected-damage @runtime-hooks) current-damage))

(defn client-get-skill-by-controllable
  [cat-id ctrl-id]
  ((:client-get-skill-by-controllable @runtime-hooks) cat-id ctrl-id))

(defn client-new-context
  [player-uuid skill-id]
  ((:client-new-context @runtime-hooks) player-uuid skill-id))

(defn client-register-context!
  [ctx]
  ((:client-register-context! @runtime-hooks) ctx))

(defn client-get-context
  [ctx-id]
  ((:client-get-context @runtime-hooks) ctx-id))

(defn client-terminate-context!
  [ctx-id reason]
  ((:client-terminate-context! @runtime-hooks) ctx-id reason))

(defn client-transition-to-alive!
  [ctx-id server-id payload]
  ((:client-transition-to-alive! @runtime-hooks) ctx-id server-id payload))

(defn client-send-context-local!
  [ctx-id channel payload]
  ((:client-send-context-local! @runtime-hooks) ctx-id channel payload))

(defn client-update-ability-data!
  [player-uuid ability-data]
  ((:client-update-ability-data! @runtime-hooks) player-uuid ability-data))

(defn client-update-resource-data!
  [player-uuid resource-data]
  ((:client-update-resource-data! @runtime-hooks) player-uuid resource-data))

(defn client-update-cooldown-data!
  [player-uuid cooldown-data]
  ((:client-update-cooldown-data! @runtime-hooks) player-uuid cooldown-data))

(defn client-update-preset-data!
  [player-uuid preset-data]
  ((:client-update-preset-data! @runtime-hooks) player-uuid preset-data))

(defn client-build-hud-render-data
  [hud-model screen-width screen-height cooldown-data]
  ((:client-build-hud-render-data @runtime-hooks) hud-model screen-width screen-height cooldown-data))

(defn client-req-learn-skill!
  [skill-id extra callback]
  ((:client-req-learn-skill! @runtime-hooks) skill-id extra callback))

(defn client-req-level-up!
  [callback]
  ((:client-req-level-up! @runtime-hooks) callback))

(defn client-req-set-activated!
  [activated callback]
  ((:client-req-set-activated! @runtime-hooks) activated callback))

(defn client-req-set-preset-slot!
  [preset-idx key-idx cat-id ctrl-id callback]
  ((:client-req-set-preset-slot! @runtime-hooks) preset-idx key-idx cat-id ctrl-id callback))

(defn client-req-switch-preset!
  [preset-idx callback]
  ((:client-req-switch-preset! @runtime-hooks) preset-idx callback))

(defn client-open-skill-tree-screen!
  [player-uuid learn-context]
  ((:client-open-skill-tree-screen! @runtime-hooks) player-uuid learn-context))

(defn client-build-skill-tree-render-data
  []
  ((:client-build-skill-tree-render-data @runtime-hooks)))

(defn client-handle-skill-tree-hover!
  [mouse-x mouse-y]
  ((:client-handle-skill-tree-hover! @runtime-hooks) mouse-x mouse-y))

(defn client-handle-skill-tree-click!
  [mouse-x mouse-y]
  ((:client-handle-skill-tree-click! @runtime-hooks) mouse-x mouse-y))

(defn client-close-skill-tree-screen!
  []
  ((:client-close-skill-tree-screen! @runtime-hooks)))

(defn client-open-preset-editor-screen!
  [player-uuid]
  ((:client-open-preset-editor-screen! @runtime-hooks) player-uuid))

(defn client-build-preset-editor-render-data
  []
  ((:client-build-preset-editor-render-data @runtime-hooks)))

(defn client-handle-preset-editor-click!
  [mouse-x mouse-y]
  ((:client-handle-preset-editor-click! @runtime-hooks) mouse-x mouse-y))

(defn client-close-preset-editor-screen!
  []
  ((:client-close-preset-editor-screen! @runtime-hooks)))

(defn client-poll-particle-effects
  []
  ((:client-poll-particle-effects @runtime-hooks)))

(defn client-poll-sound-effects
  []
  ((:client-poll-sound-effects @runtime-hooks)))

(defn client-tick-keys!
  [key-state-fn get-player-uuid-fn]
  ((:client-tick-keys! @runtime-hooks) key-state-fn get-player-uuid-fn))

(defn client-trigger-mode-switch!
  [player-uuid]
  ((:client-trigger-mode-switch! @runtime-hooks) player-uuid))