(ns cn.li.ac.tutorial.item
  "Tutorial item (MisakaCloud Terminal) registration and right-click behavior.

  Separated from cn.li.ac.item.components because the tutorial item carries
  tutorial-specific business logic (terminal GUI open, achievement trigger)."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(defonce-guard tutorial-item-installed?)

;; ============================================================================
;; Right-click handler
;; ============================================================================

(def ^:private tutorial-on-right-click
  (fn [event-data]
    (let [{:keys [player side]} event-data]
      ;; Client side: open tutorial GUI (original AC GuiTutorial)
      ;; Only open when clicking in the air — if targeting a block,
      ;; let the block handler take priority (prevents GUI stacking).
      (when (= side :client)
        (let [targeting-block? (boolean (entity/player-raytrace-block player 5.0 false))]
          (when-not targeting-block?
            (when-let [open-fn (requiring-resolve
                                'cn.li.ac.terminal.client.actions/open-tutorial!)]
              (open-fn player)))))
      ;; Server side: trigger open_misaka_cloud achievement
      ;; (original AC MSG_TRIGGER → ACAdvancements.trigger)
      (when (= side :server)
        (try
          (when-let [trigger-fn (requiring-resolve
                                 'cn.li.ac.achievement.dispatcher/trigger-custom-event!)]
            (trigger-fn (uuid/player-uuid player) "open_misaka_cloud"))
          (catch Throwable _ nil)))
      ;; Item is NOT consumed (matches original AC: EnumActionResult.SUCCESS)
      {:consume? false})))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-tutorial-item!
  []
  (with-init-guard tutorial-item-installed?
    (idsl/register-item!
      (idsl/create-item-spec
        "tutorial"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["教程物品"]
                      :model-texture "tutorial"}
         :on-right-click tutorial-on-right-click}))
    (log/info "Tutorial item (MisakaCloud Terminal) registered")))
