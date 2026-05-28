(ns cn.li.mcmod.hooks.core
  "Platform-neutral bridge for power runtime lifecycle hooks.

  forge/fabric adapters invoke these functions from platform events.
  content modules register concrete handlers during core initialization."
  (:require [cn.li.mcmod.content.registry :as content-registry]))

(def ^:private noop
  (fn [& _] nil))

(defn- default-runtime-hooks-state []
  {:on-player-login! noop
   :on-player-logout! noop
   :on-server-stop! noop
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
   :client-new-context (fn [_ _] nil)
   :client-register-context! noop
   :client-get-context (fn [_] nil)
   :client-terminate-context! noop
   :client-transition-to-alive! noop
   :client-send-context-local! noop
   :client-build-overlay-plan (fn [_ _ _ _] nil)
   :client-build-hud-render-data (fn [_ _ _ _] nil)
   :client-open-managed-screen! (fn [_ _] nil)
   :client-build-managed-screen-draw-ops (fn [_ _ _] [])
   :client-build-managed-screen-render-data (fn [_] nil)
   :client-handle-managed-screen-hover! noop
   :client-handle-managed-screen-click! (fn [_ _ _] false)
   :client-handle-managed-screen-char-typed! noop
   :client-close-managed-screen! noop
   :client-poll-particle-effects (fn [_owner] [])
   :client-poll-sound-effects (fn [_owner] [])
   :client-tick-keys! noop
   :client-active-contexts (fn [] {})
   :client-latest-sync (fn [_] nil)
   :client-register-push-handlers! noop
   :client-notify-visual-event! noop
   :client-show-combat-notice! noop
   :client-enqueue-level-effect! noop
   :client-build-level-effect-plan (fn [_ _ _ & _] nil)
   :client-tick-level-effects! noop
   :client-slot-visual-state (fn [_ _] :idle)
   :client-visual-state (fn [_ _] nil)
   :client-on-slot-key-down! noop
   :client-on-slot-key-tick! noop
   :client-on-slot-key-up! noop
   :client-on-slot-key-abort! noop
   :client-on-movement-key-down! noop
   :client-on-movement-key-tick! noop
   :client-on-movement-key-up! noop
   :client-on-slot-wheel! noop
   :client-clear-owner-state! noop
   :client-abort-all! noop
   :client-tick! noop
   :client-tick-hand-effects! noop
   :client-drain-camera-pitch-deltas! (fn [_owner] [])
   :client-current-hand-transform (fn [] nil)})

;; ============================================================================
;; Runtime Container
;; ============================================================================

(defn create-hooks-core-runtime
  ([] (create-hooks-core-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.hooks.core/runtime ::hooks-core-runtime
    :state* (or state* (atom (default-runtime-hooks-state)))}))

(defonce ^:private installed-hooks-core-runtime
  (create-hooks-core-runtime))

(defn- hooks-core-state-atom []
  (:state* @installed-hooks-core-runtime))

(defn- hooks-core-state-snapshot []
  @(hooks-core-state-atom))

(defn- update-hooks-core-state! [f & args]
  (apply swap! (hooks-core-state-atom) f args))

(def ^:dynamic *client-session-id*
  "Client runtime session id bound by platform client adapters while invoking
  client-side hooks. Content code must fail fast when this is not available
  instead of falling back to a process-global default owner."
  nil)

(def ^:dynamic *player-state-owner*
  "Runtime owner bound by platform server/client entry points while accessing
  content-owned player state. Expected server shape: {:server-session-id ...}.
  Content storage combines this owner with the requested player UUID."
  nil)

(defn register-power-runtime-hooks!
  "Register/replace power runtime hook fns."
  [hooks]
  (update-hooks-core-state! merge hooks)
  nil)

(defn register-action!
  "Register a content-owned action descriptor through the neutral registry."
  [descriptor]
  (content-registry/register-action! descriptor))

(defn dispatch-action!
  "Dispatch a content action by opaque id with host-provided context/payload."
  [action-id context payload]
  (content-registry/dispatch-action! action-id context payload))

(defn register-sync-descriptor!
  "Register a content-owned sync descriptor through the neutral registry."
  [descriptor]
  (content-registry/register-sync-descriptor! descriptor))

(defn list-sync-descriptors
  "List content-owned sync descriptors known to the neutral registry."
  []
  (content-registry/list-sync-descriptors))

(defn apply-sync!
  "Apply a sync descriptor by opaque id with host-provided context/payload."
  [sync-id context payload]
  (content-registry/apply-sync! sync-id context payload))

(defn register-player-persistence-descriptor!
  "Register a content-owned player persistence descriptor."
  [descriptor]
  (content-registry/register-player-persistence-descriptor! descriptor))

(defn list-player-persistence-descriptors
  "List player persistence descriptors known to the neutral registry."
  []
  (content-registry/list-player-persistence-descriptors))

(defn register-client-input-descriptor!
  "Register a content-owned client input descriptor through the neutral registry."
  [descriptor]
  (content-registry/register-client-input-descriptor! descriptor))

(defn emit-client-input!
  "Emit a neutral client input event by opaque id."
  [input-id context payload]
  (content-registry/emit-client-input! input-id context payload))

(defn on-player-login!
  [player-uuid]
  ((:on-player-login! (hooks-core-state-snapshot)) player-uuid))

(defn on-player-logout!
  [player-uuid]
  ((:on-player-logout! (hooks-core-state-snapshot)) player-uuid))

(defn on-server-stop!
  [session-id]
  ((:on-server-stop! (hooks-core-state-snapshot)) session-id))

(defn on-player-clone!
  [old-player-uuid new-player-uuid]
  ((:on-player-clone! (hooks-core-state-snapshot)) old-player-uuid new-player-uuid))

(defn on-player-death!
  [player-uuid]
  ((:on-player-death! (hooks-core-state-snapshot)) player-uuid))

(defn on-player-dimension-change!
  [player-uuid from-dim to-dim]
  ((:on-player-dimension-change! (hooks-core-state-snapshot)) player-uuid from-dim to-dim))

(defn on-player-tick!
  [player-uuid]
  ((:on-player-tick! (hooks-core-state-snapshot)) player-uuid))

(defn init-damage-handlers!
  []
  ((:init-damage-handlers! (hooks-core-state-snapshot))))

(defn list-player-uuids
  []
  ((:list-player-uuids (hooks-core-state-snapshot))))

(defn build-sync-payload
  [player-uuid]
  ((:build-sync-payload (hooks-core-state-snapshot)) player-uuid))

(defn mark-player-clean!
  [player-uuid]
  ((:mark-player-clean! (hooks-core-state-snapshot)) player-uuid))

(defn get-player-state
  [player-uuid]
  ((:get-player-state (hooks-core-state-snapshot)) player-uuid))

(defn runtime-activated?
  "Returns true if the installed runtime marks `player-uuid` active."
  [player-uuid]
  (boolean ((:runtime-activated? (hooks-core-state-snapshot)) player-uuid)))

(defn set-player-state!
  [player-uuid state]
  ((:set-player-state! (hooks-core-state-snapshot)) player-uuid state))

(defn get-or-create-player-state!
  [player-uuid]
  ((:get-or-create-player-state! (hooks-core-state-snapshot)) player-uuid))

(defn fresh-player-state
  []
  ((:fresh-player-state (hooks-core-state-snapshot))))

(defn register-network-handlers!
  []
  ((:register-network-handlers! (hooks-core-state-snapshot))))

(defn subscribe-achievement-trigger!
  [handler]
  ((:subscribe-achievement-trigger! (hooks-core-state-snapshot)) handler))

(defn register-context-route-fns!
  [fns-map]
  ((:register-context-route-fns! (hooks-core-state-snapshot)) fns-map))

(defn register-context-send-fns!
  [fns-map]
  ((:register-context-send-fns! (hooks-core-state-snapshot)) fns-map))

(defn get-context-player-uuid
  [ctx-id]
  ((:get-context-player-uuid (hooks-core-state-snapshot)) ctx-id))

(defn register-damage-handler!
  [handler-id handler-fn priority]
  ((:register-damage-handler! (hooks-core-state-snapshot)) handler-id handler-fn priority))

(defn unregister-damage-handler!
  [handler-id]
  ((:unregister-damage-handler! (hooks-core-state-snapshot)) handler-id))

(defn get-active-damage-handlers
  []
  ((:get-active-damage-handlers (hooks-core-state-snapshot))))

(defn process-damage-interception
  [player-id attacker-id damage damage-source]
  ((:process-damage-interception (hooks-core-state-snapshot)) player-id attacker-id damage damage-source))

(defn should-cancel-attack-interception?
  [player-id attacker-id damage damage-source]
  ((:should-cancel-attack-interception? (hooks-core-state-snapshot)) player-id attacker-id damage damage-source))

(defn run-attack-precheck-side-effects!
  [player-id attacker-id damage damage-source]
  ((:run-attack-precheck-side-effects! (hooks-core-state-snapshot)) player-id attacker-id damage damage-source))

(defn resolve-item-use-action
  [item-id]
  ((:resolve-item-use-action (hooks-core-state-snapshot)) item-id))

(defn on-runtime-item-action!
  [action player-uuid payload]
  ((:on-runtime-item-action! (hooks-core-state-snapshot)) action player-uuid payload))

(defn build-item-use-plan
  [player-uuid item-id activated? side]
  ((:build-item-use-plan (hooks-core-state-snapshot)) player-uuid item-id activated? side))

(defn compute-aoe-damage
  [origin-pos target-pos radius damage falloff?]
  ((:compute-aoe-damage (hooks-core-state-snapshot)) origin-pos target-pos radius damage falloff?))

(defn select-reflection-target
  [current-entity-uuid current-pos candidates max-radius]
  ((:select-reflection-target (hooks-core-state-snapshot)) current-entity-uuid current-pos candidates max-radius))

(defn compute-reflected-damage
  [current-damage]
  ((:compute-reflected-damage (hooks-core-state-snapshot)) current-damage))

(defn get-reflection-search-radius
  []
  ((:reflection-search-radius (hooks-core-state-snapshot))))

(defn client-new-context
  [player-uuid context-id]
  ((:client-new-context (hooks-core-state-snapshot)) player-uuid context-id))

(defn client-register-context!
  [ctx]
  ((:client-register-context! (hooks-core-state-snapshot)) ctx))

(defn client-get-context
  [ctx-id]
  ((:client-get-context (hooks-core-state-snapshot)) ctx-id))

(defn client-terminate-context!
  [ctx-id reason]
  ((:client-terminate-context! (hooks-core-state-snapshot)) ctx-id reason))

(defn client-transition-to-alive!
  [ctx-id server-id payload]
  ((:client-transition-to-alive! (hooks-core-state-snapshot)) ctx-id server-id payload))

(defn client-send-context-local!
  [ctx-id channel payload]
  ((:client-send-context-local! (hooks-core-state-snapshot)) ctx-id channel payload))

(defn client-build-overlay-plan
  [player-uuid screen-width screen-height overlay-state]
  ((:client-build-overlay-plan (hooks-core-state-snapshot)) player-uuid screen-width screen-height overlay-state))

(defn client-build-hud-render-data
  [hud-model screen-width screen-height render-state]
  ((:client-build-hud-render-data (hooks-core-state-snapshot)) hud-model screen-width screen-height render-state))

(defn client-open-managed-screen!
  [screen-key payload]
  ((:client-open-managed-screen! (hooks-core-state-snapshot)) screen-key payload))

(defn client-build-managed-screen-draw-ops
  [screen-key mouse-x mouse-y]
  ((:client-build-managed-screen-draw-ops (hooks-core-state-snapshot)) screen-key mouse-x mouse-y))

(defn client-build-managed-screen-render-data
  [screen-key]
  ((:client-build-managed-screen-render-data (hooks-core-state-snapshot)) screen-key))

(defn client-handle-managed-screen-hover!
  [screen-key mouse-x mouse-y]
  ((:client-handle-managed-screen-hover! (hooks-core-state-snapshot)) screen-key mouse-x mouse-y))

(defn client-handle-managed-screen-click!
  [screen-key mouse-x mouse-y]
  ((:client-handle-managed-screen-click! (hooks-core-state-snapshot)) screen-key mouse-x mouse-y))

(defn client-handle-managed-screen-char-typed!
  [screen-key ch]
  ((:client-handle-managed-screen-char-typed! (hooks-core-state-snapshot)) screen-key ch))

(defn client-close-managed-screen!
  [screen-key]
  ((:client-close-managed-screen! (hooks-core-state-snapshot)) screen-key))

(defn client-poll-particle-effects
  ([]
   (client-poll-particle-effects nil))
  ([owner]
   ((:client-poll-particle-effects (hooks-core-state-snapshot)) owner)))

(defn client-poll-sound-effects
  ([]
   (client-poll-sound-effects nil))
  ([owner]
   ((:client-poll-sound-effects (hooks-core-state-snapshot)) owner)))

(defn client-tick-keys!
  [key-state-fn get-player-uuid-fn]
  ((:client-tick-keys! (hooks-core-state-snapshot)) key-state-fn get-player-uuid-fn))

(defn client-active-contexts
  []
  ((:client-active-contexts (hooks-core-state-snapshot))))

(defn client-latest-sync
  [player-uuid]
  ((:client-latest-sync (hooks-core-state-snapshot)) player-uuid))

(defn client-register-push-handlers!
  []
  ((:client-register-push-handlers! (hooks-core-state-snapshot))))

(defn client-notify-visual-event!
  [event-key payload]
  ((:client-notify-visual-event! (hooks-core-state-snapshot)) event-key payload))

(defn client-show-combat-notice!
  [notice-id payload]
  ((:client-show-combat-notice! (hooks-core-state-snapshot)) notice-id payload))

(defn client-enqueue-level-effect!
  [effect-id payload]
  ((:client-enqueue-level-effect! (hooks-core-state-snapshot)) effect-id payload))

(defn client-build-level-effect-plan
  ([camera-pos hand-center-pos tick]
   ((:client-build-level-effect-plan (hooks-core-state-snapshot)) camera-pos hand-center-pos tick))
  ([camera-pos hand-center-pos tick frame-context]
   ((:client-build-level-effect-plan (hooks-core-state-snapshot)) camera-pos hand-center-pos tick frame-context)))

(defn client-tick-level-effects!
  []
  ((:client-tick-level-effects! (hooks-core-state-snapshot))))

(defn client-slot-visual-state
  [player-uuid key-idx]
  ((:client-slot-visual-state (hooks-core-state-snapshot)) player-uuid key-idx))

(defn client-visual-state
  [state-key payload]
  ((:client-visual-state (hooks-core-state-snapshot)) state-key payload))

(defn client-on-slot-key-down!
  [player-uuid key-idx]
  ((:client-on-slot-key-down! (hooks-core-state-snapshot)) player-uuid key-idx))

(defn client-on-slot-key-tick!
  [player-uuid key-idx]
  ((:client-on-slot-key-tick! (hooks-core-state-snapshot)) player-uuid key-idx))

(defn client-on-slot-key-up!
  [player-uuid key-idx]
  ((:client-on-slot-key-up! (hooks-core-state-snapshot)) player-uuid key-idx))

(defn client-on-slot-key-abort!
  [player-uuid key-idx]
  ((:client-on-slot-key-abort! (hooks-core-state-snapshot)) player-uuid key-idx))

(defn client-on-movement-key-down!
  [player-uuid movement-key]
  ((:client-on-movement-key-down! (hooks-core-state-snapshot)) player-uuid movement-key))

(defn client-on-movement-key-tick!
  [player-uuid movement-key]
  ((:client-on-movement-key-tick! (hooks-core-state-snapshot)) player-uuid movement-key))

(defn client-on-movement-key-up!
  [player-uuid movement-key]
  ((:client-on-movement-key-up! (hooks-core-state-snapshot)) player-uuid movement-key))

(defn client-on-slot-wheel!
  [player-uuid key-idx delta]
  ((:client-on-slot-wheel! (hooks-core-state-snapshot)) player-uuid key-idx delta))

(defn client-clear-owner-state!
  [owner]
  ((:client-clear-owner-state! (hooks-core-state-snapshot)) owner))

(defn client-abort-all!
  []
  ((:client-abort-all! (hooks-core-state-snapshot))))

(defn client-tick!
  []
  ((:client-tick! (hooks-core-state-snapshot))))

(defn client-tick-hand-effects!
  []
  ((:client-tick-hand-effects! (hooks-core-state-snapshot))))

(defn client-drain-camera-pitch-deltas!
  ([]
   (client-drain-camera-pitch-deltas! nil))
  ([owner]
   ((:client-drain-camera-pitch-deltas! (hooks-core-state-snapshot)) owner)))

(defn client-current-hand-transform
  []
  ((:client-current-hand-transform (hooks-core-state-snapshot))))
