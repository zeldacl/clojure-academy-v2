(ns cn.li.ac.ability.platform-bridge
  "AC ability runtime bindings for platform lifecycle hooks.

  This namespace keeps platform adapters decoupled from direct ac namespace imports."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.state.store :as ability-store]
            [cn.li.ac.ability.server.network :as ability-network]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.server.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.ac.ability.server.damage.entity :as entity-damage-runtime]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.client.hud :as hud-renderer]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.api :as client-api]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree-screen]
            [cn.li.ac.ability.client.screens.preset-editor :as preset-editor-screen]
            [cn.li.ac.ability.client.screens.location-teleport :as location-teleport-screen]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.ability.client.delegate-state :as delegate-state]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private hooks-installed? (atom false))
(defonce ^:private client-push-handlers-registered? (atom false))
(defonce ^:private vm-wave-circles (atom []))
(defonce ^:private vm-wave-last-spawn-ms (atom 0))

(defn- vec-reflection-active?
  [player-uuid]
  (boolean
    (some (fn [[_ctx-id ctx-data]]
            (and (= (:player-uuid ctx-data) player-uuid)
                 (toggle/is-toggle-active? ctx-data :vec-reflection)))
          (ctx/get-all-contexts))))

(defn- vec-deviation-active?
  [player-uuid]
  (boolean
    (some (fn [[_ctx-id ctx-data]]
            (and (= (:player-uuid ctx-data) player-uuid)
                 (toggle/is-toggle-active? ctx-data :vec-deviation)))
          (ctx/get-all-contexts))))

(defn- ease-in-out
  [t]
  (if (< t 0.5)
    (* 2.0 t t)
    (- 1.0 (* 2.0 (- 1.0 t) (- 1.0 t)))))

(defn- spawn-vm-wave-circle
  [screen-width screen-height now-ms]
  (let [cx (/ (double screen-width) 2.0)
        cy (/ (double screen-height) 2.0)
        offset-r (+ 8.0 (* (rand) 42.0))
        angle (* (rand) 2.0 Math/PI)
        life-ms (+ 520 (rand-int 260))
        start-size (+ 8.0 (* (rand) 6.0))
        end-size (+ 36.0 (* (rand) 32.0))]
    {:x (+ cx (* offset-r (Math/cos angle)))
     :y (+ cy (* offset-r (Math/sin angle)))
     :born-ms now-ms
     :life-ms life-ms
     :start-size start-size
     :end-size end-size
     :seed (rand)}))

(defn- update-vm-wave-circles!
  [active? screen-width screen-height now-ms]
  (swap! vm-wave-circles
         (fn [circles]
           (let [alive (->> circles
                            (filter (fn [{:keys [born-ms life-ms]}]
                                      (< (- now-ms (long born-ms)) (long life-ms))))
                            vec)
                 needs-spawn? (and active?
                                   (>= (- now-ms (long @vm-wave-last-spawn-ms)) 90))
                 spawned (if needs-spawn?
                           (conj alive (spawn-vm-wave-circle screen-width screen-height now-ms))
                           alive)]
             (when needs-spawn?
               (reset! vm-wave-last-spawn-ms now-ms))
             (if active?
               spawned
               ;; no longer active: keep fading remnants briefly
               (if (seq spawned) spawned []))))))

(defn- vm-wave-elements
  [now-ms]
  (->> @vm-wave-circles
       (map (fn [{:keys [x y born-ms life-ms start-size end-size seed]}]
              (let [elapsed (double (max 0 (- now-ms (long born-ms))))
                    life (double (max 1 life-ms))
                    t (min 1.0 (/ elapsed life))
                    s (+ start-size (* (- end-size start-size) t))
                    fade-in (min 1.0 (/ t 0.2))
                    fade-out (if (> t 0.6) (/ (- 1.0 t) 0.4) 1.0)
                    pulse (+ 0.85 (* 0.15 (Math/sin (+ (* t 12.0) (* seed Math/PI)))))
                    alpha (* 0.72 (ease-in-out t) fade-in fade-out pulse)
                    hs (/ s 2.0)]
                {:kind :blit-texture
                 :texture "my_mod:textures/effects/glow_circle.png"
                 :x (int (- x hs))
                 :y (int (- y hs))
                 :w (int s)
                 :h (int s)
                 :alpha (double (max 0.0 (min 1.0 alpha)))})))
       (filter #(pos? (:alpha %)))
       vec))

(defn- build-hud-model-from-state
  [player-state activated-override]
  (when player-state
    (let [resource-data (:resource-data player-state)
          preset-data-map (:preset-data player-state)
          activated (if (contains? activated-override :value)
                      (:value activated-override)
                      (boolean (:activated resource-data)))]
      {:cp {:cur (double (or (:cur-cp resource-data) 0.0))
            :max (double (or (:max-cp resource-data) 1.0))}
       :overload {:cur (double (or (:cur-overload resource-data) 0.0))
                  :max (double (or (:max-overload resource-data) 1.0))
                  :fine (boolean (get resource-data :overload-fine true))}
       :active-slots (vec (preset-data/get-active-slots preset-data-map))
       :activated activated})))

(defn- hud-render-data->overlay-elements
  [hud-render-data screen-width screen-height]
  (let [cp-bar (some-> (:cp-bar hud-render-data)
                       (assoc :kind :bar)
                       (dissoc :type))
        overload-bar (some-> (:overload-bar hud-render-data)
                             (assoc :kind :bar)
                             (dissoc :type))
        activation-indicator (some-> (:activation-indicator hud-render-data)
                                     (assoc :kind :activation-indicator)
                                     (dissoc :type))
        skill-slots (mapv (fn [slot]
                            (-> slot
                                (assoc :kind :skill-slot)
                                (dissoc :type)))
                          (or (:skill-slots hud-render-data) []))
        preset-indicator (some-> (:preset-indicator hud-render-data)
                                 (assoc :kind :preset-indicator
                                        :x (int (/ screen-width 2))
                                        :y (- screen-height 60))
                                 (dissoc :type))
        ;; Overload pulse when overload bar > 80%
        overload-pulse (when-let [ol-bar (:overload-bar hud-render-data)]
                         (let [pct (double (or (:percent ol-bar) 0.0))]
                           (when (> pct 0.8)
                             {:kind :overload-pulse
                              :intensity (* (- pct 0.8) 5.0)})))]
    (vec (concat (keep identity [cp-bar overload-bar activation-indicator
                                 preset-indicator overload-pulse])
                 skill-slots))))

(defn- build-client-overlay-plan
  [player-uuid screen-width screen-height overlay-state]
  (let [player-state (ps/get-player-state player-uuid)
        activated-override {:value (if (contains? overlay-state :activated-override)
                                     (boolean (:activated-override overlay-state))
                                     (boolean (get-in player-state [:resource-data :activated] false)))}
        hud-model (build-hud-model-from-state player-state activated-override)
        cooldown-data (:cooldown-data player-state)
        activate-hint (client-keybinds/get-activate-hint player-uuid)
        preset-state (client-keybinds/get-preset-switch-state)
        hud-render-data (hud-renderer/build-hud-render-data
                         hud-model screen-width screen-height cooldown-data
                         :player-uuid player-uuid
                         :activate-hint activate-hint
                         :preset-state preset-state
                         :now-ms (long (or (:now-ms overlay-state) (System/currentTimeMillis))))
        base-elements (hud-render-data->overlay-elements hud-render-data screen-width screen-height)
        reflection-active? (vec-reflection-active? player-uuid)
        deviation-active? (vec-deviation-active? player-uuid)
        vm-wave-active? (or reflection-active? deviation-active?)
        now-ms (long (or (:now-ms overlay-state) (System/currentTimeMillis)))
        phase (double (/ (mod now-ms 1200) 1200.0))
        _ (update-vm-wave-circles! vm-wave-active? screen-width screen-height now-ms)
        vm-wave (vm-wave-elements now-ms)
        crosshair (when reflection-active?
                    {:kind :vec-reflection-crosshair
                     :x (int (/ screen-width 2))
                     :y (int (/ screen-height 2))
                     :phase phase
                     :intensity 1.0})]
    {:elements (vec (concat base-elements
                            vm-wave
                            (keep identity [crosshair])))}))

(defn- on-context-channel-push!
  [{:keys [ctx-id channel payload]}]
  ;; Delegate to FX registry — content _fx.clj files self-register their channels
  (fx-registry/dispatch-fx-channel! ctx-id channel payload)
  ;; location-teleport UI open is not an FX channel; handle inline
  (when (= channel :location-teleport/ui-open)
    (location-teleport-screen/apply-server-payload! payload)
    (client-bridge/open-location-teleport-screen! nil payload))
  (ctx/ctx-send-to-local! ctx-id channel payload))

(defn- register-client-push-handlers!
  []
  (when (compare-and-set! client-push-handlers-registered? false true)
    (net-client/register-push-handler!
      catalog/MSG-SYNC-ABILITY
      (fn [{:keys [uuid ability-data]}]
        (when (and uuid ability-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-ability-data! uuid (constantly ability-data)))))

    (net-client/register-push-handler!
      catalog/MSG-SYNC-RESOURCE
      (fn [{:keys [uuid resource-data]}]
        (when (and uuid resource-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-resource-data! uuid (constantly resource-data)))))

    (net-client/register-push-handler!
      catalog/MSG-SYNC-COOLDOWN
      (fn [{:keys [uuid cooldown-data]}]
        (when (and uuid cooldown-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-cooldown-data! uuid (constantly cooldown-data)))))

    (net-client/register-push-handler!
      catalog/MSG-SYNC-PRESET
      (fn [{:keys [uuid preset-data]}]
        (when (and uuid preset-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-preset-data! uuid (constantly preset-data)))))

    (net-client/register-push-handler!
      catalog/MSG-CTX-ESTABLISH
      (fn [{:keys [ctx-id server-id]}]
        (ctx/transition-to-alive! ctx-id server-id nil)))

    (net-client/register-push-handler!
      catalog/MSG-CTX-TERMINATE
      (fn [{:keys [ctx-id]}]
        (ctx/terminate-context! ctx-id nil)))

    (net-client/register-push-handler!
      catalog/MSG-CTX-TERMINATED
      (fn [{:keys [ctx-id]}]
        (ctx/terminate-context! ctx-id nil)))

    (net-client/register-push-handler!
      catalog/MSG-CTX-CHANNEL
      on-context-channel-push!)
    (log/info "Ability client push handlers registered")))

(defonce ^:private lifecycle-subscriptions-registered? (atom false))

(defn- register-lifecycle-subscriptions!
  "Subscribe to domain events for resource recalculation and state reactions."
  []
  (when (compare-and-set! lifecycle-subscriptions-registered? false true)
    ;; On level change: reset add-max growth, recalc max values
    (evt/subscribe-ability-event!
     evt/EVT-LEVEL-CHANGE
     (fn [{:keys [uuid new-level]}]
       (when (and uuid new-level)
         (ps/update-resource-data! uuid
                                   (fn [rd]
                                     (-> rd
                                         (rdata/reset-add-max)
                                         (rdata/recalc-max-values new-level)))))))

    ;; On skill learn: recalc max values (new controllable may affect formulas)
    (evt/subscribe-ability-event!
     evt/EVT-SKILL-LEARN
     (fn [{:keys [uuid]}]
       (when uuid
         (when-let [state (ps/get-player-state uuid)]
           (let [level (get-in state [:ability-data :level] 1)]
             (ps/update-resource-data! uuid
                                       (fn [rd]
                                         (svc-res/recalc-max-for-level rd level uuid))))))))

    ;; On category change: deactivate ability and recalc
    (evt/subscribe-ability-event!
     evt/EVT-CATEGORY-CHANGE
     (fn [{:keys [uuid]}]
       (when uuid
         (ps/update-resource-data! uuid rdata/set-activated false)
         (when-let [state (ps/get-player-state uuid)]
           (let [level (get-in state [:ability-data :level] 1)]
             (ps/update-resource-data! uuid
                                       (fn [rd]
                                         (svc-res/recalc-max-for-level rd level uuid))))))))

    (log/info "Ability lifecycle event subscriptions registered")))

(defn install-ability-runtime-hooks!
  "Install AC handlers for platform power runtime callbacks."
  []
  (when (compare-and-set! hooks-installed? false true)
    (ability-store/install-store!)
    (register-lifecycle-subscriptions!)
    (power-runtime/register-power-runtime-hooks!
      {:on-player-login!
       (fn [player-uuid]
         ;; Ensure in-memory state exists even when persistent payload is empty.
         (ps/get-or-create-player-state! player-uuid))

       :on-player-logout!
       (fn [player-uuid]
         (ctx-mgr/abort-player-contexts! player-uuid)
         (ps/remove-player-state! player-uuid))

       :on-player-clone!
       (fn [_old-player-uuid _new-player-uuid]
         nil)

       :on-player-death!
       (fn [player-uuid]
         (ctx-mgr/abort-player-contexts! player-uuid))

       :get-skills-for-category
       (fn [cat-id]
         (vec (skill/get-skills-for-category cat-id)))

       :on-player-tick!
       (fn [player-uuid]
         (ps/get-or-create-player-state! player-uuid)
         (ps/server-tick-player! player-uuid nil)
         (delayed-projectiles/tick-player! player-uuid)
         (ctx-mgr/tick-context-manager!))

       :list-player-uuids
       (fn []
         (keys @ps/player-states))

       :build-sync-payload
       (fn [player-uuid]
         (when-let [state (ps/get-player-state player-uuid)]
           {:uuid player-uuid
            :ability-data (:ability-data state)
            :resource-data (:resource-data state)
            :cooldown-data (:cooldown-data state)
            :preset-data (:preset-data state)}))

       :mark-player-clean!
       (fn [player-uuid]
         (ps/mark-clean! player-uuid))

       :get-player-state
       (fn [player-uuid]
         (ps/get-player-state player-uuid))

       :set-player-state!
       (fn [player-uuid state]
         (ps/set-player-state! player-uuid state))

       :get-or-create-player-state!
       (fn [player-uuid]
         (ps/get-or-create-player-state! player-uuid))

       :fresh-player-state
       (fn []
         (ps/fresh-state))

       :register-network-handlers!
       (fn []
         (ability-network/register-handlers!))

       :subscribe-achievement-trigger!
       (fn [handler]
         (evt/subscribe-ability-event! evt/EVT-ACHIEVEMENT-TRIGGER handler))

       :register-context-route-fns!
       (fn [fns-map]
         (ctx/register-route-fns! fns-map))

       :register-context-send-fns!
       (fn [fns-map]
         (ctx-mgr/register-send-fns! fns-map))

       :get-context-player-uuid
       (fn [ctx-id]
         (when-let [ctx-map (ctx/get-context ctx-id)]
           (:player-uuid ctx-map)))

       :register-damage-handler!
       (fn [handler-id handler-fn priority]
         (damage-runtime/register-damage-handler! handler-id handler-fn priority))

       :unregister-damage-handler!
       (fn [handler-id]
         (damage-runtime/unregister-damage-handler! handler-id))

       :get-active-damage-handlers
       (fn []
         (damage-runtime/get-active-handlers))

       :process-damage-interception
       (fn [player-id attacker-id damage damage-source]
         (damage-runtime/process-damage! player-id attacker-id damage damage-source))

       :should-cancel-attack-interception?
       (fn [player-id attacker-id damage damage-source]
         (damage-handler/should-cancel-attack? player-id attacker-id damage damage-source))

       :resolve-item-use-action
       (fn [item-id]
         (item-actions/resolve-item-action item-id))

       :on-runtime-item-action!
       (fn [action player-uuid payload]
         (item-actions/on-item-action! action player-uuid payload))

       :build-item-use-plan
       (fn [_player-uuid item-id _activated? _side]
         (when-let [action (item-actions/resolve-item-action item-id)]
           (let [entity-spawn (item-actions/get-item-entity-spawn item-id)
                 server-actions (cond-> [{:kind :consume-item :count 1 :unless-instabuild? true}
                                        {:kind :domain-action :action action :payload {}}]
                                  entity-spawn (conj {:kind :spawn-scripted-effect
                                                      :entity-id (:entity-id entity-spawn)
                                                      :speed (double (or (:speed entity-spawn) 0.0))}))]
             {:server-actions server-actions
              :client-actions [{:kind :notify-local-effect}]
              :consume? true})))

       :compute-aoe-damage
       (fn [origin-pos target-pos radius damage falloff?]
         (entity-damage-runtime/compute-aoe-damage origin-pos target-pos radius damage falloff?))

       :select-reflection-target
       (fn [current-entity-uuid current-pos candidates max-radius]
         (entity-damage-runtime/select-next-reflection-target current-entity-uuid current-pos candidates max-radius))

       :compute-reflected-damage
       (fn [current-damage]
         (entity-damage-runtime/compute-reflected-damage current-damage))

       :client-get-skill-by-controllable
       (fn [cat-id ctrl-id]
         (skill/get-skill-by-controllable cat-id ctrl-id))

       :client-new-context
       (fn [player-uuid skill-id]
         (ctx/new-context player-uuid skill-id))

       :client-register-context!
       (fn [ctx-map]
         (ctx/register-context! ctx-map))

       :client-get-context
       (fn [ctx-id]
         (ctx/get-context ctx-id))

       :client-terminate-context!
       (fn [ctx-id reason]
         (ctx/terminate-context! ctx-id reason))

       :client-transition-to-alive!
       (fn [ctx-id server-id payload]
         (ctx/transition-to-alive! ctx-id server-id payload))

       :client-send-context-local!
       (fn [ctx-id channel payload]
         (ctx/ctx-send-to-local! ctx-id channel payload))

       :client-update-ability-data!
       (fn [player-uuid ability-data]
         (ps/get-or-create-player-state! player-uuid)
         (ps/update-ability-data! player-uuid (constantly ability-data)))

       :client-update-resource-data!
       (fn [player-uuid resource-data]
         (ps/get-or-create-player-state! player-uuid)
         (ps/update-resource-data! player-uuid (constantly resource-data)))

       :client-update-cooldown-data!
       (fn [player-uuid cooldown-data]
         (ps/get-or-create-player-state! player-uuid)
         (ps/update-cooldown-data! player-uuid (constantly cooldown-data)))

       :client-update-preset-data!
       (fn [player-uuid preset-data]
         (ps/get-or-create-player-state! player-uuid)
         (ps/update-preset-data! player-uuid (constantly preset-data)))

       :client-build-hud-render-data
       (fn [hud-model screen-width screen-height cooldown-data]
         (hud-renderer/build-hud-render-data hud-model screen-width screen-height cooldown-data))

       :client-slot-visual-state
       (fn [player-uuid key-idx]
         (let [active-ctxs (ctx/get-all-contexts-for-player player-uuid)
               skill-id (client-keybinds/get-skill-id-for-slot-public player-uuid key-idx)]
           (:state (delegate-state/delegate-state-for-slot active-ctxs skill-id))))

       :client-build-overlay-plan
       (fn [player-uuid screen-width screen-height overlay-state]
         (build-client-overlay-plan player-uuid screen-width screen-height overlay-state))

       :client-req-learn-skill!
       (fn [skill-id extra callback]
         (client-api/req-learn-skill! skill-id extra callback))

       :client-req-level-up!
       (fn [callback]
         (client-api/req-level-up! callback))

       :client-req-set-activated!
       (fn [activated callback]
         (client-api/req-set-activated! activated callback))

       :client-req-set-preset-slot!
       (fn [preset-idx key-idx cat-id ctrl-id callback]
         (client-api/req-set-preset-slot! preset-idx key-idx cat-id ctrl-id callback))

       :client-req-switch-preset!
       (fn [preset-idx callback]
         (client-api/req-switch-preset! preset-idx callback))

       :client-open-skill-tree-screen!
       (fn [player-uuid learn-context]
         (skill-tree-screen/open-screen! player-uuid learn-context))

       :client-build-skill-tree-render-data
       (fn []
         (skill-tree-screen/build-screen-render-data))

       :client-build-skill-tree-draw-ops
       (fn [mouse-x mouse-y]
         (skill-tree-screen/build-draw-ops mouse-x mouse-y))

       :client-handle-skill-tree-hover!
       (fn [mouse-x mouse-y]
         (skill-tree-screen/on-mouse-move mouse-x mouse-y))

       :client-handle-skill-tree-click!
       (fn [mouse-x mouse-y]
         (skill-tree-screen/handle-screen-click! mouse-x mouse-y))

       :client-close-skill-tree-screen!
       (fn []
         (skill-tree-screen/close-screen!))

       :client-open-preset-editor-screen!
       (fn [player-uuid]
         (preset-editor-screen/open-screen! player-uuid))

       :client-build-preset-editor-render-data
       (fn []
         (preset-editor-screen/build-preset-editor-render-data))

       :client-handle-preset-editor-click!
       (fn [mouse-x mouse-y]
         (preset-editor-screen/handle-screen-click! mouse-x mouse-y))

       :client-close-preset-editor-screen!
       (fn []
         (preset-editor-screen/close-screen!))

       :client-open-location-teleport-screen!
       (fn [player-uuid payload]
         (location-teleport-screen/open-screen! player-uuid payload))

       :client-build-location-teleport-draw-ops
       (fn [mouse-x mouse-y]
         (location-teleport-screen/build-draw-ops mouse-x mouse-y))

       :client-handle-location-teleport-hover!
       (fn [mouse-x mouse-y]
         (location-teleport-screen/on-mouse-move mouse-x mouse-y))

       :client-handle-location-teleport-click!
       (fn [mouse-x mouse-y]
         (location-teleport-screen/handle-screen-click! mouse-x mouse-y))

       :client-handle-location-teleport-char-typed!
       (fn [ch]
         (location-teleport-screen/handle-char-typed! ch))

       :client-close-location-teleport-screen!
       (fn []
         (location-teleport-screen/close-screen!))

       :client-poll-particle-effects
       (fn []
         (client-particles/poll-particle-effects!))

       :client-poll-sound-effects
       (fn []
         (client-sounds/poll-sound-effects!))

       :client-register-push-handlers!
       (fn []
         (register-client-push-handlers!))

       :client-enqueue-level-effect!
       (fn [effect-id payload]
         (level-effects/enqueue-level-effect! effect-id payload))

       :client-build-level-effect-plan
       (fn [camera-pos hand-center-pos tick]
         (level-effects/build-level-effect-plan camera-pos hand-center-pos tick))

       :client-tick-level-effects!
       (fn []
         (level-effects/tick-level-effects!))

       :client-tick-keys!
       (fn [key-state-fn get-player-uuid-fn]
         (binding [cn.li.ac.ability.client.keybinds/*get-player-uuid-fn* get-player-uuid-fn]
           (client-keybinds/tick-keys! key-state-fn)))

       :client-trigger-mode-switch!
       (fn [player-uuid]
         (client-keybinds/trigger-mode-switch! player-uuid))

       :client-trigger-preset-switch!
       (fn [player-uuid]
         (client-keybinds/switch-preset! player-uuid))

       :client-tick-hand-effects!
       (fn []
         (hand-effects/tick-hand-effects!))

       :client-drain-camera-pitch-deltas!
       (fn []
         (hand-effects/drain-camera-pitch-deltas!))

       :client-current-hand-transform
       (fn []
         (hand-effects/current-hand-transform))})
    (log/info "AC ability runtime hooks installed"))
  nil)