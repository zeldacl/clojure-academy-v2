(ns cn.li.mcmod.hooks.core
  "Platform-neutral bridge for power runtime lifecycle hooks.

  forge/fabric adapters invoke these functions from platform events.
  ac registers concrete handlers during core initialization.")

(def ^:private noop
  (fn [& _] nil))

(defonce ^:private runtime-hooks
  (atom {:on-player-login! noop
         :on-player-logout! noop
         :on-player-clone! noop
         :on-player-death! noop
         :on-player-dimension-change! noop
         :on-player-tick! noop
         :init-damage-handlers! noop
         :list-player-uuids (fn [] [])
         :build-sync-payload (fn [_] nil)
         :mark-player-clean! noop
         :get-player-state (fn [_] nil)
         :set-player-state! noop
         :get-or-create-player-state! (fn [_] nil)
         :fresh-player-state (fn [] nil)
         :runtime-activated? (fn [_] false)
         :register-network-handlers! noop
         :subscribe-achievement-trigger! (fn [_] nil)
         :register-context-route-fns! noop
         :register-context-send-fns! noop
         :get-context-player-uuid (fn [_] nil)
         :register-damage-handler! (fn [_ _ _] false)
         :unregister-damage-handler! (fn [_] false)
         :get-active-damage-handlers (fn [] [])
         :process-damage-interception (fn [_ _ damage _] damage)
         :should-cancel-attack-interception? (fn [_ _ _ _] false)
         :run-attack-precheck-side-effects! (fn [_ _ _ _] false)
         :resolve-item-use-action (fn [_] nil)
         :on-runtime-item-action! noop
         :build-item-use-plan (fn [_ _ _ _] nil)
         :compute-aoe-damage (fn [_ _ _ damage _] damage)
         :select-reflection-target (fn [_ _ _ _] nil)
         :compute-reflected-damage identity
         :reflection-search-radius (fn [] 0.0)
         :get-skills-for-category (fn [_cat-id] [])
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
         :client-build-overlay-plan (fn [_ _ _ _] nil)
         :client-build-hud-render-data (fn [_ _ _ _] nil)
         :client-req-learn-skill! noop
         :client-req-level-up! noop
         :client-req-set-activated! noop
         :client-req-set-preset-slot! noop
         :client-req-switch-preset! noop
         :client-open-managed-screen! (fn [_ _] nil)
         :client-build-managed-screen-draw-ops (fn [_ _ _] [])
         :client-build-managed-screen-render-data (fn [_] nil)
         :client-handle-managed-screen-hover! noop
         :client-handle-managed-screen-click! (fn [_ _ _] false)
         :client-handle-managed-screen-char-typed! noop
         :client-close-managed-screen! noop
         :client-poll-particle-effects (fn [] [])
         :client-poll-sound-effects (fn [] [])
         :client-tick-keys! noop
         :client-trigger-mode-switch! noop
         :client-trigger-preset-switch! noop
         :client-active-contexts (fn [] {})
         :client-latest-sync (fn [_] nil)
         :client-register-push-handlers! noop
         :client-notify-visual-event! noop
         :client-enqueue-level-effect! noop
         :client-build-level-effect-plan (fn [_ _ _] nil)
         :client-tick-level-effects! noop
         :client-slot-visual-state (fn [_ _] :idle)
         :client-visual-state (fn [_ _] nil)
         :client-on-slot-key-down! noop
         :client-on-slot-key-tick! noop
         :client-on-slot-key-up! noop
         :client-on-movement-key-down! noop
         :client-on-movement-key-tick! noop
         :client-on-movement-key-up! noop
         :client-on-slot-wheel! noop
         :client-abort-all! noop
         :client-tick! noop
         :client-tick-hand-effects! noop
         :client-drain-camera-pitch-deltas! (fn [] [])
         :client-current-hand-transform (fn [] nil)}))

(defn register-power-runtime-hooks!
  "Register/replace power runtime hook fns."
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

(defn on-player-dimension-change!
  [player-uuid from-dim to-dim]
  ((:on-player-dimension-change! @runtime-hooks) player-uuid from-dim to-dim))

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

(defn runtime-activated?
  "Returns true if the installed runtime marks `player-uuid` active."
  [player-uuid]
  (boolean ((:runtime-activated? @runtime-hooks) player-uuid)))

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

(defn subscribe-achievement-trigger!
  [handler]
  ((:subscribe-achievement-trigger! @runtime-hooks) handler))

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

(defn should-cancel-attack-interception?
  [player-id attacker-id damage damage-source]
  ((:should-cancel-attack-interception? @runtime-hooks) player-id attacker-id damage damage-source))

(defn run-attack-precheck-side-effects!
  [player-id attacker-id damage damage-source]
  ((:run-attack-precheck-side-effects! @runtime-hooks) player-id attacker-id damage damage-source))

(defn resolve-item-use-action
  [item-id]
  ((:resolve-item-use-action @runtime-hooks) item-id))

(defn on-runtime-item-action!
  [action player-uuid payload]
  ((:on-runtime-item-action! @runtime-hooks) action player-uuid payload))

(defn build-item-use-plan
  [player-uuid item-id activated? side]
  ((:build-item-use-plan @runtime-hooks) player-uuid item-id activated? side))

(defn compute-aoe-damage
  [origin-pos target-pos radius damage falloff?]
  ((:compute-aoe-damage @runtime-hooks) origin-pos target-pos radius damage falloff?))

(defn select-reflection-target
  [current-entity-uuid current-pos candidates max-radius]
  ((:select-reflection-target @runtime-hooks) current-entity-uuid current-pos candidates max-radius))

(defn compute-reflected-damage
  [current-damage]
  ((:compute-reflected-damage @runtime-hooks) current-damage))

(defn get-reflection-search-radius
  []
  ((:reflection-search-radius @runtime-hooks)))

(defn get-skills-for-category
  "Returns all skill specs registered for the given category-id keyword.
  Returns [] if no skills registered or hook not installed."
  [cat-id]
  ((:get-skills-for-category @runtime-hooks) cat-id))

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

(defn client-build-overlay-plan
  [player-uuid screen-width screen-height overlay-state]
  ((:client-build-overlay-plan @runtime-hooks) player-uuid screen-width screen-height overlay-state))

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

(defn client-open-managed-screen!
  [screen-key payload]
  ((:client-open-managed-screen! @runtime-hooks) screen-key payload))

(defn client-build-managed-screen-draw-ops
  [screen-key mouse-x mouse-y]
  ((:client-build-managed-screen-draw-ops @runtime-hooks) screen-key mouse-x mouse-y))

(defn client-build-managed-screen-render-data
  [screen-key]
  ((:client-build-managed-screen-render-data @runtime-hooks) screen-key))

(defn client-handle-managed-screen-hover!
  [screen-key mouse-x mouse-y]
  ((:client-handle-managed-screen-hover! @runtime-hooks) screen-key mouse-x mouse-y))

(defn client-handle-managed-screen-click!
  [screen-key mouse-x mouse-y]
  ((:client-handle-managed-screen-click! @runtime-hooks) screen-key mouse-x mouse-y))

(defn client-handle-managed-screen-char-typed!
  [screen-key ch]
  ((:client-handle-managed-screen-char-typed! @runtime-hooks) screen-key ch))

(defn client-close-managed-screen!
  [screen-key]
  ((:client-close-managed-screen! @runtime-hooks) screen-key))

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

(defn client-trigger-preset-switch!
  [player-uuid]
  ((:client-trigger-preset-switch! @runtime-hooks) player-uuid))

(defn client-active-contexts
  []
  ((:client-active-contexts @runtime-hooks)))

(defn client-latest-sync
  [player-uuid]
  ((:client-latest-sync @runtime-hooks) player-uuid))

(defn client-register-push-handlers!
  []
  ((:client-register-push-handlers! @runtime-hooks)))

(defn client-notify-visual-event!
  [event-key payload]
  ((:client-notify-visual-event! @runtime-hooks) event-key payload))

(defn client-enqueue-level-effect!
  [effect-id payload]
  ((:client-enqueue-level-effect! @runtime-hooks) effect-id payload))

(defn client-build-level-effect-plan
  [camera-pos hand-center-pos tick]
  ((:client-build-level-effect-plan @runtime-hooks) camera-pos hand-center-pos tick))

(defn client-tick-level-effects!
  []
  ((:client-tick-level-effects! @runtime-hooks)))

(defn client-slot-visual-state
  [player-uuid key-idx]
  ((:client-slot-visual-state @runtime-hooks) player-uuid key-idx))

(defn client-visual-state
  [state-key payload]
  ((:client-visual-state @runtime-hooks) state-key payload))

(defn client-on-slot-key-down!
  [player-uuid key-idx]
  ((:client-on-slot-key-down! @runtime-hooks) player-uuid key-idx))

(defn client-on-slot-key-tick!
  [player-uuid key-idx]
  ((:client-on-slot-key-tick! @runtime-hooks) player-uuid key-idx))

(defn client-on-slot-key-up!
  [player-uuid key-idx]
  ((:client-on-slot-key-up! @runtime-hooks) player-uuid key-idx))

(defn client-on-movement-key-down!
  [player-uuid movement-key]
  ((:client-on-movement-key-down! @runtime-hooks) player-uuid movement-key))

(defn client-on-movement-key-tick!
  [player-uuid movement-key]
  ((:client-on-movement-key-tick! @runtime-hooks) player-uuid movement-key))

(defn client-on-movement-key-up!
  [player-uuid movement-key]
  ((:client-on-movement-key-up! @runtime-hooks) player-uuid movement-key))

(defn client-on-slot-wheel!
  [player-uuid key-idx delta]
  ((:client-on-slot-wheel! @runtime-hooks) player-uuid key-idx delta))

(defn client-abort-all!
  []
  ((:client-abort-all! @runtime-hooks)))

(defn client-tick!
  []
  ((:client-tick! @runtime-hooks)))

(defn client-tick-hand-effects!
  []
  ((:client-tick-hand-effects! @runtime-hooks)))

(defn client-drain-camera-pitch-deltas!
  []
  ((:client-drain-camera-pitch-deltas! @runtime-hooks)))

(defn client-current-hand-transform
  []
  ((:client-current-hand-transform @runtime-hooks)))