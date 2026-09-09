(ns cn.li.ac.item.spell-composer-dev-tool
  "spell_composer_dev_tool: a creative-mode-only item whose sole purpose is
   opening the player spell composer (see the editor execution plan) -- mirrors
   cn.li.ac.item.editor-dev-tool exactly, just targeting spell-composer/open!
   instead of the node editor (the K keybind, cn.li.ac.input-ids, is this
   screen's other entry point). Reuses developer_portable's icon (a real,
   already-shipped texture, not a new asset) as a plain flat 2D item -- no
   energy system, no 3D OBJ model. Intentionally NOT registered in
   cn.li.ac.recipe.crafting-recipes: the only way to obtain it is /give or
   the creative inventory, by design."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.client.screens.spell-composer-reactive :as spell-composer]))

(defn- open-spell-composer-dev-tool!
  "Right-click handler. Unlike the node editor's dev tool, spell-composer/
   open! takes only a player-uuid -- no file path to resolve, so there is
   no equivalent failure case here."
  [{:keys [player side]}]
  (when (= side :client)
    (if-let [player-uuid (uuid/player-uuid player)]
      (spell-composer/open! player-uuid)
      (log/warn "spell_composer_dev_tool: could not resolve a player UUID")))
  ;; :consume? true short-circuits the interaction (InteractionResult.
  ;; SUCCESS), matching editor_dev_tool.clj/tutorial_item.clj's own
  ;; right-click handlers -- it does NOT consume the item stack.
  {:consume? true})

(defn init-spell-composer-dev-tool!
  []
  (install/framework-once! ::spell-composer-dev-tool-installed?
  (fn []
    (idsl/register-item!
      (idsl/create-item-spec
        "spell_composer_dev_tool"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["法术合成器开发工具" "仅创造模式可获得"]
                      :model-texture "developer_portable_full"}
         :on-right-click open-spell-composer-dev-tool!}))
    (log/debug "Spell composer dev tool item registered"))))
