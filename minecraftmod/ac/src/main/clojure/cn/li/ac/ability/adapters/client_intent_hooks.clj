(ns cn.li.ac.ability.adapters.client-intent-hooks
  "Client-side CombatIntent composition.  No Context/slot packet runtime."
  (:require [cn.li.ac.ability.client.api :as client-api]
            [cn.li.ac.ability.client.combat-notice :as combat-notice]
            [cn.li.ac.ability.client.keybinds :as keybinds]
            [cn.li.ac.ability.client.reactive-hud :as reactive-hud]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.screens.preset-editor :as preset-editor-screen]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree-screen]
            [cn.li.ac.ability.client.screens.preset-editor-reactive :as preset-editor-reactive]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.util.resource-check :as resource-check]
            [cn.li.ac.ability.service.combat-content :as combat-content]
            [cn.li.ac.ability.messages :as messages]
            [cn.li.ac.client.combat-vfx-adapter :as combat-vfx]
            [cn.li.ac.client.toast :as toast]
            [cn.li.ac.tutorial.client.notification :as tutorial-notification]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.runtime.owner :as owner]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private intent-seq* (atom 0))
(defonce ^:private active-slots* (atom #{}))
(defonce ^:private handlers-registered?* (atom false))
(defonce ^:private notice-component*
  (delay (combat-notice/create-combat-notice-component
          {:now-ms-fn #(client-bridge/game-time-ms)})))

(defn- current-session []
  (or keybinds/*client-session-id* (runtime-hooks/client-session-id)))

(defn- client-owner [player-uuid]
  (owner/require-client-owner
   {:client-session-id (current-session)
    :player-uuid (str player-uuid)}))

(defn- slot-key [player-uuid slot]
  [(or keybinds/*client-session-id* (runtime-hooks/client-session-id))
   (str player-uuid) (long slot)])

(defn send-combat-intent! [player-uuid slot op]
  (let [intent-id (swap! intent-seq* inc)
        key (slot-key player-uuid slot)]
    (case op
      :start (swap! active-slots* conj key)
      (:release :abort) (swap! active-slots* disj key)
      nil)
    (net-client/send-to-server
     (client-owner player-uuid) messages/MSG-COMBAT-INTENT
     {:schema-version 1 :intent-id intent-id :op op :slot (long slot)
      :client-tick (long (quot (or (client-bridge/game-time-ms) 0) 50))}
     (fn [result] (when (map? result) (combat-vfx/dispatch-result! result))))
    intent-id))

(defn- combat-slot? [player-uuid slot]
  (contains? combat-content/ability-ids
            (keybinds/get-skill-id-for-slot-public player-uuid slot)))

(defn- runtime-sync-resets-input?
  [old-ability-data new-ability-data]
  (or (not= (:category-id old-ability-data) (:category-id new-ability-data))
      (and (seq (:learned-skills old-ability-data))
           (empty? (:learned-skills new-ability-data)))))

(defn- resource-sync-disables-input?
  [old-resource-data new-resource-data]
  (let [old-activated (boolean (:activated old-resource-data))
        new-activated (boolean (:activated new-resource-data))]
    (or (and old-activated (not new-activated))
        (and (resource-check/can-use-resource-data? old-resource-data)
             (not (resource-check/can-use-resource-data? new-resource-data))))))

(declare apply-client-runtime-v2! clear-owner-state!)

(defn- register-push-handlers! []
  (when (compare-and-set! handlers-registered?* false true)
    (net-client/register-push-handler! messages/MSG-COMBAT-RESULT
      (fn [result]
        (combat-vfx/dispatch-result! result)
        (doseq [[idx feedback] (map-indexed vector (:feedback result))]
          (combat-notice/show-notice! @notice-component*
                                      (current-session)
                                      (keyword (str "combat-" idx))
                                      (or feedback {:text "Combat rejected"})))))
    (net-client/register-push-handler! messages/MSG-SYNC-V2 apply-client-runtime-v2!)
    (log/info "CombatIntent push handlers registered")))

(defn- hydrate! [player-uuid domain value]
  (command-runtime/run-command-in-session!
   (or keybinds/*client-session-id* (runtime-hooks/client-session-id))
    (str player-uuid)
    {:command :hydrate-player-state domain value}))

(defn- apply-client-runtime-v2!
  [{:keys [version opcode uuid revision dirty-mask] :as payload}]
  (when (and (= 2 version) (or (= 1 opcode) (= 2 opcode)) uuid
             (integer? revision) (integer? dirty-mask))
    (let [uuid (str uuid)
          old-state (read-model/get-player-state
                     [(current-session) :client-ui-hooks uuid])
          old-revision (long (get old-state :sync-revision -1))
          mask (long dirty-mask)]
      (when (> (long revision) old-revision)
        (let [command (cond-> {:command :hydrate-player-state
                               :sync-revision (long revision)}
                        (not (zero? (bit-and mask store/ability-data-mask)))
                        (assoc :ability-data (:ability-data payload))
                        (not (zero? (bit-and mask store/resource-data-mask)))
                        (assoc :resource-data (:resource-data payload))
                        (not (zero? (bit-and mask store/cooldown-data-mask)))
                        (assoc :cooldown-data (:cooldown-data payload))
                        (not (zero? (bit-and mask store/preset-data-mask)))
                        (assoc :preset-data (:preset-data payload))
                        (not (zero? (bit-and mask store/develop-data-mask)))
                        (assoc :develop-data (:develop-data payload)))]
          (command-runtime/run-command-in-session!
           (current-session) uuid command {:mark-dirty? false})
          (when (and (not (zero? (bit-and mask store/ability-data-mask)))
                     (runtime-sync-resets-input? (:ability-data old-state)
                                                 (:ability-data payload)))
            (clear-owner-state! uuid))
          (when (and (not (zero? (bit-and mask store/resource-data-mask)))
                     (resource-sync-disables-input? (:resource-data old-state)
                                                    (:resource-data payload)))
            (clear-owner-state! uuid))
          (when-not (zero? (bit-and mask store/preset-data-mask))
            (keybinds/update-default-group! uuid)
            (preset-editor-reactive/refresh-active-screen! uuid)))))))

(defn- slot-visual-state [player-uuid slot]
  {:state (if (contains? @active-slots* (slot-key player-uuid slot))
            :active
            :idle)})

(defn- clear-owner-state! [owner-value]
  (let [uuid (str (or (:player-uuid owner-value) owner-value))]
    (swap! active-slots*
           #(into #{} (remove (fn [entry] (= uuid (second entry))) %)))
    (skill-tree-screen/close-screen! owner-value)
    (preset-editor-screen/close-screen! owner-value)
    (reactive-hud/clear-vm-wave-for-owner! [(current-session) :client-ui-hooks uuid])
    (reactive-hud/clear-charging-arcs-for-owner! [(current-session) :client-ui-hooks uuid])
    (keybinds/clear-client-keybind-state! uuid)
    (combat-vfx/clear-owner! uuid)
    (toast/cleanup-expired!)
    (tutorial-notification/cleanup-expired!)
    nil))

(defn runtime-client-ui-hooks []
  {:client-get-skill-by-controllable
   (fn [category-id ctrl-id]
     (skill-query/get-skill-by-controllable category-id ctrl-id))
   :client-send-combat-intent! send-combat-intent!
   :client-on-slot-key-down!
   (fn [player-uuid slot]
     (when (combat-slot? player-uuid slot)
       (send-combat-intent! player-uuid slot :start)))
   :client-on-slot-key-tick! (fn [_ _] nil)
   :client-on-slot-key-up!
   (fn [player-uuid slot]
     (when (combat-slot? player-uuid slot)
       (send-combat-intent! player-uuid slot :release)))
   :client-on-slot-key-abort!
   (fn [player-uuid slot] (send-combat-intent! player-uuid slot :abort))
   ;; v1 intentionally has no client movement/channel/wheel protocol.  These
   ;; hooks stay inert so loader input code cannot recreate the removed slot
   ;; network; server sessions are driven by Combat Core deadlines.
   :client-on-movement-key-down! (fn [_ _] nil)
   :client-on-movement-key-tick! (fn [_ _] nil)
   :client-on-movement-key-up! (fn [_ _] nil)
   :client-on-slot-wheel! (fn [_ _ _] nil)
   :client-slot-visual-state slot-visual-state
   :client-visual-state (fn [_ _] nil)
   :client-register-push-handlers! register-push-handlers!
   :client-clear-owner-state! clear-owner-state!
   :client-abort-all!
   (fn []
      (when-let [uuid (some-> (client-bridge/local-player-uuid) str)]
        (let [uuid uuid]
         (doseq [slot (range 4)]
           (when (contains? @active-slots* (slot-key uuid slot))
             (send-combat-intent! uuid slot :abort))))))
   :client-update-ability-data! (fn [p v] (hydrate! p :ability-data v))
   :client-update-resource-data! (fn [p v] (hydrate! p :resource-data v))
   :client-update-cooldown-data! (fn [p v] (hydrate! p :cooldown-data v))
   :client-update-preset-data! (fn [p v] (hydrate! p :preset-data v))
   :client-req-learn-skill!
   (fn [p skill extra callback]
     (client-api/req-learn-skill! (client-owner p) skill extra callback))
   :client-req-level-up!
   (fn [p callback] (client-api/req-level-up! (client-owner p) callback))
   :client-req-set-activated!
   (fn [p active callback]
     (client-api/req-set-activated! (client-owner p) active callback))
   :client-req-set-preset-slot!
   (fn [p preset key category ctrl callback]
     (client-api/req-set-preset-slot! (client-owner p) preset key category ctrl callback))
   :client-req-switch-preset!
   (fn [p preset callback]
     (client-api/req-switch-preset! (client-owner p) preset callback))
   :client-trigger-mode-switch! (fn [p] (keybinds/trigger-mode-switch! p))
   :client-trigger-preset-switch! (fn [p] (keybinds/switch-preset! p))
   :client-show-combat-notice! (fn [_ _] nil)
   :client-notify-visual-event! (fn [_ _] nil)
   :client-ui-state-snapshot (fn [] {:active-slots @active-slots*})})
