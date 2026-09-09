(ns cn.li.ac.item.editor-dev-tool
  "Creative-mode-only editor_dev_tool that opens the node editor.
   It shares the fixed sample with the G key and is a second supported
   entry point alongside direct development calls. Reuses
   developer_portable's existing icon as a plain flat 2D item (no energy
   system or 3D OBJ model). It is intentionally not registered in
   cn.li.ac.recipe.crafting-recipes; obtain it with /give or from the
   creative inventory."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as node-editor]))

(defn- open-editor-dev-tool!
  "Right-click handler. Same fixed default target as the G key (thunder-bolt.edn
   in skill mode, see node-editor-reactive/default-sample-skill
   -resource-path's own docstring on why it is fixed rather than
   resolved) -- no file-picker UI yet, so both entry points point at the
   same file until one exists."
  [{:keys [player side]}]
  (when (= side :client)
    (if-let [player-uuid (uuid/player-uuid player)]
      (try
        (if-let [path (node-editor/default-sample-skill-resource-path "ac/skills-v3/thunder-bolt.edn")]
          (node-editor/open! player-uuid path :skill)
          (log/warn "editor_dev_tool: ac/skills-v3/thunder-bolt.edn is not on-disk (packaged jar?) -- no writable path to open"))
        (catch Throwable e
          (log/stacktrace "editor_dev_tool: node editor failed to open" e)))
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
