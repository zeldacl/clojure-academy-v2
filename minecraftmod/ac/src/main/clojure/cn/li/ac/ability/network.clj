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
            [cn.li.ac.ability.model.ability-data :as adata]
            [cn.li.ac.ability.model.preset-data :as preset-data]
            [cn.li.ac.ability.service.learning  :as lrn]
            [cn.li.ac.ability.skill             :as skill]
            [cn.li.ac.ability.service.resource  :as res]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.block.developer.logic     :as dev-logic]
            [cn.li.mcmod.platform.position      :as pos]
            [cn.li.mcmod.platform.world         :as world]
            [cn.li.mcmod.platform.be            :as platform-be]
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

  (defn- developer-type-for-tile
    [tile]
    (let [bid (platform-be/get-block-id tile)
          n (name (or bid ""))]
      (if (= n "developer-advanced")
        :advanced
        :normal)))

  (defn- dist-sq-ok-for-station?
    [player tile]
    (let [raw-pos (try (pos/position-get-block-pos tile) (catch Exception _ nil))
          max-distance 8.0]
      (boolean
        (when raw-pos
          (let [bx (+ 0.5 (double (or (try (pos/pos-x raw-pos) (catch Exception _ nil))
                                      (:x raw-pos))))
                by (+ 0.5 (double (or (try (pos/pos-y raw-pos) (catch Exception _ nil))
                                      (:y raw-pos))))
                bz (+ 0.5 (double (or (try (pos/pos-z raw-pos) (catch Exception _ nil))
                                      (:z raw-pos))))]
            (< (entity/entity-distance-to-sqr player bx by bz)
               (* max-distance max-distance)))))))

  (defn- developer-controller-tile?
    [tile]
    (let [n (name (or (platform-be/get-block-id tile) ""))]
      (contains? #{"developer-normal" "developer-advanced"} n)))

  ;; ============================================================================
  ;; Skill learning
  ;; ============================================================================

  (defn- handle-req-learn-skill
    [payload player]
    (let [{:keys [skill-id pos-x pos-y pos-z]} payload
          uuid (uuid-of player)
          state (get-state uuid)
          ad (:ability-data state)
          player-level (:level ad)
          world (entity/player-get-level player)
          all-coords? (and (number? pos-x) (number? pos-y) (number? pos-z))
          tile (when (and all-coords? world)
                 (net-helpers/get-tile-at world
                   {:pos-x (long pos-x) :pos-y (long pos-y) :pos-z (long pos-z)}))
          st (when tile (or (platform-be/get-custom-state tile) {}))
          session-ok? (= (str (:user-uuid st "")) uuid)
          server-world? (and world (not (world/world-is-client-side* world)))
          station
          (when all-coords?
            (cond (not server-world?) {:ok? false :reason :not-server}
                  (not tile) {:ok? false :reason :no-tile}
                  (not (developer-controller-tile? tile)) {:ok? false :reason :wrong-block}
                  (not (dist-sq-ok-for-station? player tile)) {:ok? false :reason :distance}
                  (not session-ok?) {:ok? false :reason :session}
                  (not (:structure-valid st)) {:ok? false :reason :structure}
                  :else {:ok? true :tile tile :developer-type (developer-type-for-tile tile)}))
          do-learn!
          (fn []
            (let [{:keys [data event]} (lrn/learn-skill ad uuid skill-id)]
              (ps/update-ability-data! uuid (constantly data))
              (when event (evt/fire-ability-event! event))))]
      (when-not (adata/is-learned? ad skill-id)
        (cond
          (and all-coords? (not (:ok? station)))
          (log/debug "learn-skill rejected (station)" uuid skill-id (:reason station))

          all-coords?
          (let [dev-t (:developer-type station)
                {:keys [pass? failures]} (lrn/check-all-conditions skill-id ad player-level dev-t)]
            (if pass?
              (let [sk (skill/get-skill skill-id)
                    cost (double (skill/learning-cost (long (:level sk))))]
                (if (dev-logic/try-pull-energy! (:tile station) cost)
                  (do-learn!)
                  (log/debug "learn-skill rejected (IF)" uuid skill-id cost)))
              (log/debug "learn-skill rejected" uuid skill-id failures)))

          :else
          (let [{:keys [pass? failures]} (lrn/check-all-conditions skill-id ad player-level :normal)]
            (if pass?
              (do-learn!)
              (log/debug "learn-skill rejected" uuid skill-id failures)))))))

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
          before (boolean (:activated rd))
          {:keys [data events]} (res/set-activated rd uuid activated)
          after (boolean (:activated data))]
      (log/info "[V-TRACE][AC][SERVER][REQ-SET-ACTIVATED]"
                {:uuid uuid
                 :requested (boolean activated)
                 :before before
                 :after after
                 :events (count events)})
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
    (let [payload* (assoc payload :player player)
          ctx0 (ctx/get-context ctx-id)
          _ (when (nil? ctx0)
              (when-let [skill-id (:skill-id payload*)]
                (ctx-mgr/establish-context! (uuid-of player) ctx-id skill-id)))]
      (ctx-rt/handle-key-down! ctx-id payload* ctx-mgr/send-terminated-context!)))

  (defn- handle-skill-key-tick
    [{:keys [ctx-id] :as payload} player]
    (ctx-rt/handle-key-tick! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))

  (defn- handle-skill-key-up
    [{:keys [ctx-id] :as payload} player]
    (ctx-rt/handle-key-up! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))

  (defn- handle-skill-key-abort
    [{:keys [ctx-id] :as payload} player]
    (ctx-rt/handle-key-abort! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))

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
