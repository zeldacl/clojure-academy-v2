(ns cn.li.ac.ability.network
  "Server-side message handler registrations for the ability system.

  All handlers registered here correspond to MSG-* constants in catalog.clj.
  Incoming messages carry a payload map and a player-uuid string.

  All mutating calls go through player-state ns; no atom touched directly.
  No net.minecraft.* imports allowed."
  (:require [cn.li.mcmod.network.server         :as net-srv]
            [cn.li.mcmod.ability.catalog        :as catalog]
            [cn.li.mcmod.platform.entity        :as entity]
            [cn.li.ac.ability.player-state      :as ps]
            [cn.li.ac.ability.model.preset-data :as preset-data]
            [cn.li.ac.ability.service.learning  :as lrn]
            [cn.li.ac.ability.service.resource  :as res]
            [cn.li.ac.ability.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.service.context-runtime :as ctx-rt]
            [cn.li.ac.ability.context           :as ctx]
            [cn.li.ac.ability.event             :as evt]
            [cn.li.mcmod.util.log               :as log]))

  ;; ============================================================================
  ;; Helpers
  ;; ============================================================================

  (defn- uuid-of [player]
    (str (entity/player-get-uuid player)))

  (defn- get-state [uuid]
    (ps/get-or-create-player-state! uuid))

  ;; ============================================================================
  ;; Skill learning
  ;; ============================================================================

  (defn- handle-req-learn-skill
    [{:keys [skill-id]} player]
    (let [uuid     (uuid-of player)
          state    (get-state uuid)
          ad       (:ability-data state)
          player-level (:level ad)
          {:keys [pass? failures]} (lrn/check-all-conditions skill-id ad player-level :advanced)]
      (if-not pass?
        (log/debug "learn-skill rejected" uuid skill-id failures)
        (let [{:keys [data event]} (lrn/learn-skill ad uuid skill-id)]
          (ps/update-ability-data! uuid (constantly data))
          (when event (evt/fire-ability-event! event))))))

  ;; ============================================================================
  ;; Level up
  ;; ============================================================================

  (defn- handle-req-level-up
    [_payload player]
    (let [uuid  (uuid-of player)
          state (get-state uuid)
          ad    (:ability-data state)
          {:keys [data event]} (lrn/level-up ad uuid)]
      (when data
        (ps/update-ability-data! uuid (constantly data))
        (when event (evt/fire-ability-event! event)))))

  ;; ============================================================================
  ;; Preset management
  ;; ============================================================================

  (defn- handle-req-set-preset
    [{:keys [preset-idx key-idx cat-id ctrl-id]} player]
    (let [uuid (uuid-of player)]
      (ps/update-preset-data! uuid
                              preset-data/set-slot
                              preset-idx key-idx
                              (when (and cat-id ctrl-id) [cat-id ctrl-id]))))

  (defn- handle-req-switch-preset
    [{:keys [preset-idx]} player]
    (let [uuid (uuid-of player)]
      (ps/update-preset-data! uuid preset-data/set-active-preset preset-idx)
      (evt/fire-ability-event! {:event/type evt/EVT-PRESET-SWITCH
                                 :player-id uuid
                                 :preset    preset-idx})))

  ;; ============================================================================
  ;; Activation toggle
  ;; ============================================================================

  (defn- handle-req-set-activated
    [{:keys [activated]} player]
    (let [uuid  (uuid-of player)
          state (get-state uuid)
          rd    (:resource-data state)
    {:keys [data events]} (res/set-activated rd uuid activated)]
      (ps/update-resource-data! uuid (constantly data))
      (doseq [e events] (evt/fire-ability-event! e))))

  ;; ============================================================================
  ;; Context lifecycle
  ;; ============================================================================

  (defn- handle-ctx-begin-link
    [{:keys [ctx-id skill-id]} player]
    (ctx-mgr/establish-context! (uuid-of player) ctx-id skill-id))

  (defn- handle-ctx-keepalive
    [{:keys [ctx-id]} _player]
    (ctx/update-keepalive! ctx-id))

  (defn- handle-ctx-terminate
    [{:keys [ctx-id]} _player]
    (ctx/terminate-context! ctx-id ctx-mgr/send-terminated-context!))

  (defn- handle-ctx-channel
    [{:keys [ctx-id channel payload]} _player]
    (ctx/ctx-send-to-local! ctx-id channel payload))

  (defn- handle-skill-key-down
    [{:keys [ctx-id] :as payload} player]
    (let [ctx0 (ctx/get-context ctx-id)
          _ (when (nil? ctx0)
              (when-let [skill-id (:skill-id payload)]
                (ctx-mgr/establish-context! (uuid-of player) ctx-id skill-id)))]
      (ctx-rt/handle-key-down! ctx-id payload ctx-mgr/send-terminated-context!)))

  (defn- handle-skill-key-tick
    [{:keys [ctx-id] :as payload} _player]
    (ctx-rt/handle-key-tick! ctx-id payload ctx-mgr/send-terminated-context!))

  (defn- handle-skill-key-up
    [{:keys [ctx-id] :as payload} _player]
    (ctx-rt/handle-key-up! ctx-id payload ctx-mgr/send-terminated-context!))

  (defn- handle-skill-key-abort
    [{:keys [ctx-id] :as payload} _player]
    (ctx-rt/handle-key-abort! ctx-id payload ctx-mgr/send-terminated-context!))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-handlers! []
  (net-srv/register-handler catalog/MSG-REQ-LEARN-SKILL    handle-req-learn-skill)
  (net-srv/register-handler catalog/MSG-REQ-LEVEL-UP       handle-req-level-up)
  (net-srv/register-handler catalog/MSG-REQ-SET-PRESET     handle-req-set-preset)
  (net-srv/register-handler catalog/MSG-REQ-SWITCH-PRESET  handle-req-switch-preset)
  (net-srv/register-handler catalog/MSG-REQ-SET-ACTIVATED  handle-req-set-activated)
  (net-srv/register-handler catalog/MSG-CTX-BEGIN-LINK     handle-ctx-begin-link)
  (net-srv/register-handler catalog/MSG-CTX-KEEPALIVE      handle-ctx-keepalive)
  (net-srv/register-handler catalog/MSG-CTX-TERMINATE      handle-ctx-terminate)
  (net-srv/register-handler catalog/MSG-CTX-CHANNEL        handle-ctx-channel)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-DOWN     handle-skill-key-down)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-TICK     handle-skill-key-tick)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-UP       handle-skill-key-up)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-ABORT    handle-skill-key-abort)
  (log/info "Ability network handlers registered"))
