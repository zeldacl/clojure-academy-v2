(ns cn.li.ac.item.developer-portable
  "Portable Developer item — opens the classic AcademyCraft developer UI.

  Reuses block/developer/panel.clj entirely via the container's :on-dev-start
  callback. This matches original AcademyCraft: ItemDeveloper.onItemRightClick →
  DeveloperUI.apply(PortableDevData.get(player)) where the SAME DeveloperUI
  works for both block and portable via the IDeveloper interface."
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.block.developer.panel :as dev-panel]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Portable Container — mimics block container for panel.clj
;; ============================================================================

(def ^:private portable-max-energy 10000.0)
(def ^:private session-ns-prefix "developer.portable")

(defn- get-player-held-stack [player]
  (when player (entity/player-get-main-hand-item-stack player)))

(defn- current-energy-from-held-item [player]
  (let [stack (get-player-held-stack player)]
    (if (and stack (energy/is-energy-item-supported? stack))
      (double (energy/get-item-energy stack))
      0.0)))

(defn- update-held-item-energy! [player energy-atom]
  (reset! energy-atom (current-energy-from-held-item player)))

(defn- make-portable-on-dev-start
  "Create the :on-dev-start callback for portable instant development.
  Returns (fn [action extra callback]) that calls the appropriate API."
  [owner]
  (fn [action _extra callback]
    (case action
      :learn-skill (api/req-learn-skill! owner (-> _extra :skill-id keyword) callback)
      :level-up    (api/req-level-up! owner callback)
      ;; reset not supported on portable
      (when callback (callback {:success false :reason "not-available-on-portable"})))))

(defn make-portable-container
  "Create a container that satisfies panel.clj's interface without a tile entity.
  :on-dev-start overrides timed development with instant API calls.
  All other atoms (energy, tier, progress) match the block developer schema."
  [player owner]
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
     ;; Override: instant API calls instead of timed block dev
     :on-dev-start          (make-portable-on-dev-start owner)}))

;; ============================================================================
;; Portable GUI builder
;; ============================================================================

(defn create-portable-developer-gui
  "Build the CGUI widget tree for the portable developer screen.
  Reuses block/developer/panel.clj for all rendering — the container's
  :on-dev-start callback provides portable-specific dev handling."
  [player]
  (try
    (let [session-id (runtime-hooks/require-player-state-session-id session-ns-prefix)
          owner (read-model/canonical-client-owner
                  {:client-session-id session-id
                   :player-uuid (uuid/player-uuid player)}
                  :skill-tree)
          container (make-portable-container player owner)
          root (dev-panel/load-classic-developer-page)
          _ (tech-ui/init-cgui-root-metadata! root)]
      ;; Ensure player state exists for skill tree rendering
      (let [owner-key (read-model/owner-key owner :skill-tree)]
        (read-model/ensure-player-state! owner-key))
      ;; Hide wireless button — portable has no wireless link
      (when-let [wbtn (cgui-core/find-widget root "parent_left/panel_machine/button_wireless")]
        (cgui-core/set-visible! wbtn false))
      (when-let [wtxt (cgui-core/find-widget root "parent_left/panel_machine/text_wireless")]
        (cgui-core/set-visible! wtxt false))
      ;; Energy sync from held item each frame
      (events/on-frame root
        (fn [_] (update-held-item-energy! player (:energy container))))
      ;; Attach block panel bindings (left panel, right panel, overlays, console)
      (dev-panel/attach-classic-developer-bindings!
        root container
        {:on-wireless-click nil  ;; no wireless for portable
         :on-develop-start nil}) ;; handled by :on-dev-start on container
      (log/info "Created Portable Developer CGUI screen")
      root)
    (catch Exception e
      (log/error "Portable developer GUI:" (ex-message e))
      (throw e))))

(defn create-screen
  "Build the CGUI screen data map for the portable developer."
  [player]
  (let [root (create-portable-developer-gui player)
        session-id (runtime-hooks/require-player-state-session-id session-ns-prefix)]
    {:type :cgui-screen
     :cgui root
     :session-id session-id}))
