(ns cn.li.ac.item.editor-dev-tool
  "editor_dev_tool: a creative-mode-only item whose sole purpose is
   opening the node editor (node-editor plan) -- previously the only
   in-game entry points were the G keybind (cn.li.ac.input-ids) and
   direct REPL/test calls, and the user asked for an item as a second
   entry point alongside the keybind. Reuses developer_portable's icon
   (a real, already-shipped texture, not a new asset) as a plain flat
   2D item -- no energy system, no 3D OBJ model, none of that item's
   actual complexity, since this item does not share developer_portable's
   battery/console behavior at all. Intentionally NOT registered in
   cn.li.ac.recipe.crafting-recipes: the only way to obtain it is
   /give or the creative inventory, by design."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as node-editor]))

(defn- open-editor-dev-tool!
  "Right-click handler. Same fixed default target as the G key (thunder_
   bolt.edn in skill mode, see node-editor-reactive/default-sample-skill
   -resource-path's own docstring on why it is fixed rather than
   resolved) -- no file-picker UI yet, so both entry points point at the
   same file until one exists."
  [{:keys [player side]}]
  (when (= side :client)
    (if-let [player-uuid (uuid/player-uuid player)]
      (if-let [path (node-editor/default-sample-skill-resource-path "ac/skills/thunder_bolt.edn")]
        (node-editor/open! player-uuid path :skill)
        (log/warn "editor_dev_tool: ac/skills/thunder_bolt.edn is not on-disk (packaged jar?) -- no writable path to open"))
      (log/warn "editor_dev_tool: could not resolve a player UUID")))
  ;; :consume? true short-circuits the interaction (InteractionResult.
  ;; SUCCESS), matching tutorial_item.clj/energy_items.clj's own
  ;; right-click handlers -- it does NOT consume the item stack.
  {:consume? true})

(defn init-editor-dev-tool!
  []
  (install/framework-once! ::editor-dev-tool-installed?
  (fn []
    (idsl/register-item!
      (idsl/create-item-spec
        "editor_dev_tool"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["节点编辑器开发工具" "仅创造模式可获得"]
                      :model-texture "developer_portable_full"}
         :on-right-click open-editor-dev-tool!}))
    (log/debug "Editor dev tool item registered"))))
