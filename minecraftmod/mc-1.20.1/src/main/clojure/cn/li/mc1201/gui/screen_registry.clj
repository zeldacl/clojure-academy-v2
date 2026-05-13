(ns cn.li.mc1201.gui.screen-registry
  "Shared dispatcher for platform GUI screen factory registration."
  (:require [cn.li.mc1201.gui.screen-impl-core :as screen-core]
            [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.util.log :as log]))

(defn create-screen-creator
  "Return a platform-neutral screen creator fn.

  The returned function accepts `(menu player-inventory title)` and delegates
  to the shared screen construction / fallback path."
  [gui-id factory-fn-kw screen-opts]
  (fn [menu player-inventory title]
    (screen-core/create-screen-or-fallback
     gui-id
     menu
     player-inventory
     title
     factory-fn-kw
     screen-opts)))

(defn register-platform-screens!
  "Register all screens for a platform.

  Options:
  - :label                 human-readable log label
  - :screen-opts-fn        (fn [gui-id menu-type factory-fn-kw] -> map)
  - :register-menu-screen! (fn [gui-id menu-type creator-fn factory-fn-kw])"
  [platform {:keys [label screen-opts-fn register-menu-screen!]}]
  (log/info "Registering GUI screens" {:platform (or label platform)})
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [menu-type (gui/get-menu-type platform gui-id)
          factory-fn-kw (gui/get-screen-factory-fn-kw gui-id)
          screen-opts (if screen-opts-fn
                        (screen-opts-fn gui-id menu-type factory-fn-kw)
                        {})]
      (log/info "[SCREEN-INIT] Preparing GUI screen registration"
                {:platform (or label platform)
                 :gui-id gui-id
                 :menu-type menu-type
                 :factory-fn factory-fn-kw})
      (when menu-type
        (register-menu-screen!
         gui-id
         menu-type
          (create-screen-creator gui-id factory-fn-kw screen-opts)
         factory-fn-kw))
      (log/info "Registered screen factory for GUI ID"
                gui-id
                {:platform (or label platform)}))))