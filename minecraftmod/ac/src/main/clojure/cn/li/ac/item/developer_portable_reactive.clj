(ns cn.li.ac.item.developer-portable-reactive
  "Portable developer Presentation Application.
   The held item and timed development session remain gameplay state; the
   screen only exposes a read-only snapshot and a typed action."
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.model.develop :as dev-model]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.mcmod.platform.entity :as entity]))

(def ^:private portable-max-energy 10000.0)
(def ^:private session-ns-prefix "developer.portable")

(defn- get-player-held-stack [player]
  (when player (entity/player-get-main-hand-item-stack player)))

(defn- current-energy-from-held-item [player]
  (let [stack (get-player-held-stack player)]
    (if (and stack (energy/is-energy-item-supported? stack))
      (double (energy/get-item-energy stack)) 0.0)))

(defn- make-portable-on-dev-start [owner]
  (fn [action extra callback]
    (case action
      (:learn-skill :level-up)
      (api/req-portable-dev-start! owner action (some-> extra :skill-id keyword) callback)
      (when callback (callback {:success false :reason "not-available-on-portable"})))))

(defn make-portable-container [player owner]
  (let [player-uuid-str (or (uuid/player-uuid player) "")]
    {:energy (atom (current-energy-from-held-item player))
     :max-energy (atom portable-max-energy)
     :tier (atom :portable)
     :is-developing (atom false)
     :development-progress (atom 0.0)
     :development-complete? (atom false)
     :structure-valid (atom true)
     :user-uuid (atom player-uuid-str)
     :user-name (atom (or (entity/player-get-name player) ""))
     :player player :tile-entity nil :container-type :portable-developer
     :metadata (atom {}) :owner owner
     :on-dev-start (make-portable-on-dev-start owner)}))

(defn- state-lines [container session-id player-uuid]
  (let [data (:develop-data (store/get-player-state session-id player-uuid))
        developing? (boolean (some-> data dev-model/developing?))
        progress (double (if data (dev-model/progress data) 0.0))
        done? (boolean (some-> data dev-model/done?))
        energy (double @(:energy container))]
    {:lines [{:label (str "Energy: " (long energy) " / " (long portable-max-energy) " IF")}
             {:label (str "Development: " (cond done? "complete" developing? "in progress" :else "idle"))}
             {:label (format "Progress: %.0f%%" (* 100.0 progress))}]
     :status (if developing? "Development session active" "Ready")
     :scroll progress}))

(defn create-runtime [player]
  (let [player-uuid (uuid/player-uuid player)
        owner (read-model/local-client-owner player-uuid session-ns-prefix)]
    {:player player :owner owner
     :container (make-portable-container player owner)}))

(defn open! [player]
  (let [{:keys [owner container]} (create-runtime player)
        player-uuid (uuid/player-uuid player)
        session-id (:client-session-id owner)
        state (atom (state-lines container session-id player-uuid))
        refresh-fn* (atom nil)
        refresh! (fn []
                   (reset! state (state-lines container session-id player-uuid))
                   (when-let [f @refresh-fn*] (f @state)))]
    (let [vm (application/mount!
               (str "application/developer-portable/" player-uuid)
               "Portable Developer"
               @state
               (fn [action _current]
                 (when (= action :application/activate)
                   (refresh!))
                 @state)
               nil)]
      (reset! refresh-fn* (:refresh! vm))
      (future
        (dotimes [_ 600]
          (Thread/sleep 100)
          (when @refresh-fn* (refresh!))))
      vm)))
