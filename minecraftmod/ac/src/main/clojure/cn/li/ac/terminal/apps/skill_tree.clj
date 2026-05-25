(ns cn.li.ac.terminal.apps.skill-tree
  "Skill Tree app - Opens the existing skill tree screen."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Implementation
;; ============================================================================

(defn open-skill-tree-gui
  "Open the skill tree GUI for a player."
  ([player]
   (open-skill-tree-gui player nil))
  ([player learn-context]
  (log/info "Opening skill tree from terminal for player:" (entity/player-get-name player))
  ;; Reuse existing skill tree screen via platform bridge
  (let [uuid-str (uuid/player-uuid player)]
    (client-bridge/open-screen! :ac/skill-tree {:player-uuid uuid-str
                                                :learn-context learn-context}))))

;; ============================================================================
;; App Registration
;; ============================================================================

(def skill-tree-app
  {:id :skill-tree
   :name "Skill Tree"
  :icon "my_mod:textures/guis/apps/skill_tree/icon.png"
   :description "View and manage your abilities"
   :gui-fn 'cn.li.ac.terminal.apps.skill-tree/open-skill-tree-gui
   :category :abilities})

(defonce-guard skill-tree-app-installed?)

(defn init-skill-tree-app!
  []
  (with-init-guard skill-tree-app-installed?
    (reg/register-app! skill-tree-app)))
