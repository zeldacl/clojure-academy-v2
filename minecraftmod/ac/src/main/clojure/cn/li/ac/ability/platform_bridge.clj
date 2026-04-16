(ns cn.li.ac.ability.platform-bridge
  "AC ability runtime bindings for platform lifecycle hooks.

  This namespace keeps platform adapters decoupled from direct ac namespace imports."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-lifecycle]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.model.preset-data :as preset-data]
            [cn.li.ac.ability.store :as ability-store]
            [cn.li.ac.ability.network :as ability-network]
            [cn.li.ac.ability.damage-runtime :as damage-runtime]
            [cn.li.ac.ability.entity-damage-runtime :as entity-damage-runtime]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.client.hud-renderer :as hud-renderer]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client-api :as client-api]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree-screen]
            [cn.li.ac.ability.client.screens.preset-editor :as preset-editor-screen]
            [cn.li.ac.ability.client.screens.location-teleport :as location-teleport-screen]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.damage-handler :as damage-handler]
            [cn.li.ac.content.ability.electromaster.railgun :as railgun]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private hooks-installed? (atom false))
(defonce ^:private client-push-handlers-registered? (atom false))

(defn- vec-reflection-active?
  [player-uuid]
  (boolean
    (some (fn [[_ctx-id ctx-data]]
            (and (= (:player-uuid ctx-data) player-uuid)
                 (toggle/is-toggle-active? ctx-data :vec-reflection)))
          (ctx/get-all-contexts))))

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
  [hud-render-data]
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
                          (or (:skill-slots hud-render-data) []))]
    (vec (concat (keep identity [cp-bar overload-bar activation-indicator])
                 skill-slots))))

(defn- build-client-overlay-plan
  [player-uuid screen-width screen-height overlay-state]
  (let [player-state (ps/get-player-state player-uuid)
        activated-override {:value (if (contains? overlay-state :activated-override)
                                     (boolean (:activated-override overlay-state))
                                     (boolean (get-in player-state [:resource-data :activated] false)))}
        hud-model (build-hud-model-from-state player-state activated-override)
        cooldown-data (:cooldown-data player-state)
        hud-render-data (hud-renderer/build-hud-render-data hud-model screen-width screen-height cooldown-data)
        base-elements (hud-render-data->overlay-elements hud-render-data)
        reflection-active? (vec-reflection-active? player-uuid)
        now-ms (long (or (:now-ms overlay-state) (System/currentTimeMillis)))
        phase (double (/ (mod now-ms 1200) 1200.0))
        crosshair (when reflection-active?
                    {:kind :vec-reflection-crosshair
                     :x (int (/ screen-width 2))
                     :y (int (/ screen-height 2))
                     :phase phase
                     :intensity 1.0})]
    {:elements (if crosshair
                 (conj base-elements crosshair)
                 base-elements)}))

(defn- on-context-channel-push!
  [{:keys [ctx-id channel payload]}]
  (case channel
    (:railgun/fx-shot :railgun/fx-reflect)
    (level-effects/enqueue-level-effect! :railgun-shot payload)

    :thunder-bolt/fx-perform
    (level-effects/enqueue-level-effect! :thunder-bolt-strike payload)

    :thunder-clap/fx-start
    (level-effects/enqueue-level-effect! :thunder-clap {:mode :start})

    :thunder-clap/fx-update
    (level-effects/enqueue-level-effect! :thunder-clap {:mode :update
                               :ticks (long (or (:ticks payload) 0))
                               :charge-ratio (double (or (:charge-ratio payload) 0.0))
                               :target (get payload :target)})

    :thunder-clap/fx-end
    (level-effects/enqueue-level-effect! :thunder-clap {:mode :end
                               :performed? (boolean (:performed? payload))})

    :mag-movement/fx-start
    (level-effects/enqueue-level-effect! :mag-movement {:mode :start
                                                         :target (get payload :target)})

    :mag-movement/fx-update
    (level-effects/enqueue-level-effect! :mag-movement {:mode :update
                                                         :target (get payload :target)})

    :mag-movement/fx-end
    (level-effects/enqueue-level-effect! :mag-movement {:mode :end})

    :mark-teleport/fx-start
    (level-effects/enqueue-level-effect! :mark-teleport {:mode :start})

    :mark-teleport/fx-update
    (level-effects/enqueue-level-effect! :mark-teleport {:mode :update
                               :target (:target payload)
                               :distance (double (or (:distance payload) 0.0))})

    :mark-teleport/fx-end
    (level-effects/enqueue-level-effect! :mark-teleport {:mode :end})

    :mark-teleport/fx-perform
    (level-effects/enqueue-level-effect! :mark-teleport {:mode :perform
                               :target (:target payload)
                               :distance (double (or (:distance payload) 0.0))})

    :meltdowner/fx-start
    (level-effects/enqueue-level-effect! :meltdowner {:mode :start})

    :meltdowner/fx-update
    (level-effects/enqueue-level-effect! :meltdowner {:mode :update
                                                       :ticks (long (or (:ticks payload) 0))
                                                       :charge-ratio (double (or (:charge-ratio payload) 0.0))})

    :meltdowner/fx-end
    (level-effects/enqueue-level-effect! :meltdowner {:mode :end
                                                       :performed? (boolean (:performed? payload))})

    :meltdowner/fx-perform
    (level-effects/enqueue-level-effect! :meltdowner {:mode :perform
                                                       :charge-ticks (int (or (:charge-ticks payload) 20))
                                                       :beam-length (double (or (:beam-length payload) 30.0))
                                                       :start (:start payload)
                                                       :end (:end payload)})

    :meltdowner/fx-reflect
    (level-effects/enqueue-level-effect! :meltdowner {:mode :reflect
                                                       :start (:start payload)
                                                       :end (:end payload)})

    :blood-retrograde/fx-start
    (level-effects/enqueue-level-effect! :blood-retrograde {:mode :start})

    :blood-retrograde/fx-update
    (level-effects/enqueue-level-effect! :blood-retrograde {:mode :update
                                                             :ticks (long (or (:ticks payload) 0))
                                                             :charge-ratio (double (or (:charge-ratio payload) 0.0))})

    :blood-retrograde/fx-end
    (level-effects/enqueue-level-effect! :blood-retrograde {:mode :end
                                                             :performed? (boolean (:performed? payload))})

    :blood-retrograde/fx-perform
    (level-effects/enqueue-level-effect! :blood-retrograde {:mode :perform
                                                             :sound-pos (:sound-pos payload)
                                                             :splashes (:splashes payload)
                                                             :sprays (:sprays payload)})

    :directed-blastwave/fx-start
    (level-effects/enqueue-level-effect! :directed-blastwave {:mode :start})

    :directed-blastwave/fx-update
    (level-effects/enqueue-level-effect! :directed-blastwave {:mode :update
                                                               :charge-ticks (long (or (:charge-ticks payload) 0))
                                                               :punched? (boolean (:punched? payload))})

    :directed-blastwave/fx-perform
    (level-effects/enqueue-level-effect! :directed-blastwave {:mode :perform
                                                               :pos (:pos payload)
                                                               :look-dir (:look-dir payload)
                                                               :charge-ticks (long (or (:charge-ticks payload) 0))})

    :directed-blastwave/fx-end
    (level-effects/enqueue-level-effect! :directed-blastwave {:mode :end
                                                               :performed? (boolean (:performed? payload))})

    :vec-accel/fx-start
    (level-effects/enqueue-level-effect! :vec-accel {:mode :start})

    :vec-accel/fx-update
    (level-effects/enqueue-level-effect! :vec-accel
                       {:mode :update
                        :charge-ticks (long (or (:charge-ticks payload) 0))
                        :can-perform? (boolean (:can-perform? payload))
                        :look-dir (:look-dir payload)
                        :init-vel (:init-vel payload)})

    :vec-accel/fx-perform
    (level-effects/enqueue-level-effect! :vec-accel {:mode :perform})

    :vec-accel/fx-end
    (level-effects/enqueue-level-effect! :vec-accel {:mode :end
                              :performed? (boolean (:performed? payload))})

    :vec-deviation/fx-start
    (level-effects/enqueue-level-effect! :vec-deviation {:mode :start})

    :vec-deviation/fx-end
    (level-effects/enqueue-level-effect! :vec-deviation {:mode :end})

    :vec-deviation/fx-stop-entity
    (level-effects/enqueue-level-effect! :vec-deviation {:mode :stop-entity
                                :x (double (or (:x payload) 0.0))
                                :y (double (or (:y payload) 0.0))
                                :z (double (or (:z payload) 0.0))
                                :marked? (boolean (:marked? payload))})

    :vec-deviation/fx-play
    (level-effects/enqueue-level-effect! :vec-deviation {:mode :play
                                :x (double (or (:x payload) 0.0))
                                :y (double (or (:y payload) 0.0))
                                :z (double (or (:z payload) 0.0))})

    :vec-reflection/fx-start
    (level-effects/enqueue-level-effect! :vec-reflection {:mode :start})

    :vec-reflection/fx-end
    (level-effects/enqueue-level-effect! :vec-reflection {:mode :end})

    :vec-reflection/fx-reflect-entity
    (level-effects/enqueue-level-effect! :vec-reflection {:mode :reflect-entity
                   :x (double (or (:x payload) 0.0))
                   :y (double (or (:y payload) 0.0))
                   :z (double (or (:z payload) 0.0))})

    :vec-reflection/fx-play
    (level-effects/enqueue-level-effect! :vec-reflection {:mode :play
                   :x (double (or (:x payload) 0.0))
                   :y (double (or (:y payload) 0.0))
                   :z (double (or (:z payload) 0.0))})

    :groundshock/fx-start
    (hand-effects/enqueue-hand-effect! :groundshock {:mode :start})

    :groundshock/fx-update
    (hand-effects/enqueue-hand-effect! :groundshock {:mode :update
                                                      :charge-ticks (long (or (:charge-ticks payload) 0))})

    :groundshock/fx-perform
    (do
      (hand-effects/enqueue-hand-effect! :groundshock {:mode :perform})
      (level-effects/enqueue-level-effect! :groundshock {:mode :perform
                                                         :affected-blocks (:affected-blocks payload)
                                                         :broken-blocks (:broken-blocks payload)}))

    :groundshock/fx-end
    (hand-effects/enqueue-hand-effect! :groundshock {:mode :end
                                                      :performed? (boolean (:performed? payload))})

    :directed-shock/fx-start
    (hand-effects/enqueue-hand-effect! :directed-shock {:mode :start})

    :directed-shock/fx-perform
    (hand-effects/enqueue-hand-effect! :directed-shock {:mode :perform})

    :directed-shock/fx-end
    (hand-effects/enqueue-hand-effect! :directed-shock {:mode :end
                                                         :performed? (boolean (:performed? payload))})

    :plasma-cannon/fx-start
    (level-effects/enqueue-level-effect! :plasma-cannon {:mode :start})

    :plasma-cannon/fx-update
    (level-effects/enqueue-level-effect! :plasma-cannon
                                         {:mode         :update
                                          :charge-ticks (long (or (:charge-ticks payload) 0))
                                          :fully-charged? (boolean (:fully-charged? payload))
                                          :charge-pos   (:charge-pos payload)
                                          :flight-ticks (long (or (:flight-ticks payload) 0))
                                          :state        (or (:state payload) :charging)
                                          :destination  (:destination payload)})

    :plasma-cannon/fx-perform
    (level-effects/enqueue-level-effect! :plasma-cannon {:mode :perform
                                                          :pos  (:pos payload)})

    :plasma-cannon/fx-end
    (level-effects/enqueue-level-effect! :plasma-cannon {:mode       :end
                                                          :performed? (boolean (:performed? payload))})

    :storm-wing/fx-start
    (level-effects/enqueue-level-effect! :storm-wing {:mode :start
                                                       :charge-ticks (long (or (:charge-ticks payload) 70))})

    :storm-wing/fx-update
    (level-effects/enqueue-level-effect! :storm-wing {:mode :update
                                                       :phase (or (:phase payload) :charging)
                                                       :charge-ticks (long (or (:charge-ticks payload) 0))
                                                       :charge-ratio (double (or (:charge-ratio payload) 0.0))})

    :storm-wing/fx-end
    (level-effects/enqueue-level-effect! :storm-wing {:mode :end})

    :storm-wing/move-dir
    ;; Client sends WASD-derived movement direction; route to server-side context
    nil

    :location-teleport/ui-open
    (do
      (location-teleport-screen/apply-server-payload! payload)
      (client-bridge/open-location-teleport-screen! nil payload))

    nil)
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

(defn install-ability-runtime-hooks!
  "Install AC handlers for platform ability lifecycle callbacks."
  []
  (when (compare-and-set! hooks-installed? false true)
    (ability-store/install-store!)
    (ability-lifecycle/register-ability-runtime-hooks!
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

       :on-player-tick!
       (fn [player-uuid]
         (ps/get-or-create-player-state! player-uuid)
         (ps/server-tick-player! player-uuid nil)
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
       (fn [player-id attacker-id damage _damage-source]
         (if-let [vec-reflection-ns (find-ns 'cn.li.ac.content.ability.vecmanip.vec-reflection)]
           (if-let [precheck-fn (ns-resolve vec-reflection-ns 'can-cancel-attack?)]
             (boolean (precheck-fn player-id attacker-id damage))
             false)
           false))

       :resolve-item-use-action
       (fn [item-id]
         (cond
           (= item-id "ac:app_skill_tree") :open-skill-tree
           (or (= item-id "my_mod:coin")
               (= item-id "ac:coin")) :railgun-coin-throw
           :else nil))

       :on-ability-item-action!
       (fn [action player-uuid payload]
         (case action
           :railgun-coin-throw
           (railgun/register-coin-throw! player-uuid payload)
           nil))

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

       :init-damage-handlers!
       (fn []
         (damage-handler/init-damage-handlers!))})
    (log/info "AC ability runtime hooks installed"))
  nil)