(ns cn.li.ac.item.developer-portable-reactive
  "Complete reactive replacement for developer_portable.clj.
   Signal-driven: portable container + reactive developer screen + energy sync."
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

;; ============================================================================
;; Portable container (preserved from old developer_portable.clj — pure logic)
;; ============================================================================

(def ^:private portable-max-energy 10000.0)
(def ^:private session-ns-prefix "developer.portable")

(defn- get-player-held-stack [player]
  (when player (entity/player-get-main-hand-item-stack player)))

(defn- current-energy-from-held-item [player]
  (let [stack (get-player-held-stack player)]
    (if (and stack (energy/is-energy-item-supported? stack))
      (double (energy/get-item-energy stack)) 0.0)))

(defn- make-portable-on-dev-start [owner]
  (fn [action _extra callback]
    (case action
      :learn-skill (api/req-learn-skill! owner (-> _extra :skill-id keyword) callback)
      :level-up    (api/req-level-up! owner callback)
      (when callback (callback {:success false :reason "not-available-on-portable"})))))

(defn make-portable-container [player owner]
  (let [player-uuid-str (or (uuid/player-uuid player) "")
        player-name-str (or (entity/player-get-name player) "")]
    {:energy                (atom (current-energy-from-held-item player))
     :max-energy            (atom portable-max-energy)
     :tier                  (atom :portable)
     :is-developing         (atom false)
     :development-progress  (atom 0.0)
     :development-complete? (atom false)
     :user-uuid             (atom player-uuid-str)
     :user-name             (atom player-name-str)
     :player                player
     :tile-entity           nil
     :container-type        :portable-developer
     :structure-valid       (atom true)
     :metadata              (atom {})
     :on-dev-start          (make-portable-on-dev-start owner)}))

;; ============================================================================
;; Reactive developer screen
;; ============================================================================

(defn create-runtime [player]
  (let [session-id (runtime-hooks/require-player-state-session-id session-ns-prefix)
        owner (read-model/canonical-client-owner
                {:client-session-id session-id :player-uuid (uuid/player-uuid player)}
                :skill-tree)
        container (make-portable-container player owner)
        r (rt/create-runtime)
        clock (rt/clock-ms-sig r)
        safe-val #(some-> % deref)]
    ;; Ensure player state for skill tree
    (let [owner-key (read-model/owner-key owner :skill-tree)]
      (read-model/ensure-player-state! owner-key))
    ;; Energy signal (sync from held item each frame)
    (rt/put-user-signal! r :energy
      (sig/computed-d [clock] (fn [_] (current-energy-from-held-item player))))
    ;; Tier signal
    (rt/put-user-signal! r :tier-str
      (sig/computed-o [clock] (fn [_] (str "Tier: " (name (or @(:tier container) :portable))))))
    ;; Dev progress signal
    (rt/put-user-signal! r :dev-progress
      (sig/computed-d [clock] (fn [_] (double (or @(:development-progress container) 0.0)))))
    ;; Dev status signal
    (rt/put-user-signal! r :dev-status
      (sig/computed-o [clock] (fn [_] (if @(:is-developing container) "DEVELOPING" "IDLE"))))
    ;; Store container for external access
    (rt/put-user-signal! r :container container)
    {:runtime r :container container :owner owner}))

(defn open! [{:keys [runtime]}]
  (bridge/open-reactive-screen! runtime "Portable Developer"))
