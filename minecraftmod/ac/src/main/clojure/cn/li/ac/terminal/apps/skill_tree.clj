(ns cn.li.ac.terminal.apps.skill-tree
  "Skill Tree app - Opens the existing skill tree screen."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Implementation
;; ============================================================================

(defn open-skill-tree-gui
  "Open the skill tree GUI for a player."
  [player]
  (log/info "Opening skill tree from terminal for player:" (entity/player-get-name player))
  ;; Reuse existing skill tree screen via platform bridge
  (let [uuid-str (str (entity/player-get-uuid player))]
    (client-bridge/open-skill-tree-screen! uuid-str)))

;; ============================================================================
;; App Registration
;; ============================================================================

(def skill-tree-app
  {:id :skill-tree
   :name "Skill Tree"
   :icon "academy:textures/guis/apps/skill_tree/icon.png"
   :description "View and manage your abilities"
   :gui-fn 'cn.li.ac.terminal.apps.skill-tree/open-skill-tree-gui
   :category :abilities})

(defonce ^:private skill-tree-app-installed? (atom false))

(defn init-skill-tree-app!
  []
  (when (compare-and-set! skill-tree-app-installed? false true)
    (reg/register-app! skill-tree-app)))
