(ns cn.li.mc1201.gui.provider-bridge-core
  "Shared GUI provider-bridge helpers used by Forge/Fabric wrappers."
  (:require [cn.li.mc1201.gui.provider-common :as provider-common]
            [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.util.log :as log]))

(defn create-menu-from-provider!
  "Build a menu bridge from provider callback context.

  Required opts keys:
  - :gui-id
  - :tile-entity
  - :window-id
  - :player
  - :platform-key
  - :create-container-fn    (fn [handler gui-id player world pos] -> clj-container)
  - :create-menu-bridge-fn  (fn [window-id menu-type clj-container] -> menu)

  Optional keys:
  - :log-prefix             string"
  [{:keys [gui-id
           tile-entity
           window-id
           player
           platform-key
           create-container-fn
           create-menu-bridge-fn
           log-prefix]
    :or {log-prefix "[MENU-PROVIDER]"}}]
  (let [handler (gui/get-gui-handler)
        world (.level player)
        pos (provider-common/tile->pos tile-entity player)]
    (log/info log-prefix "createMenu called: gui-id=" gui-id "window-id=" window-id "player=" (.getGameProfile player))
    (log/info log-prefix "Creating server-side container...")
    (let [clj-container (create-container-fn handler gui-id player world pos)]
      (when-not clj-container
        (throw (ex-info "Failed to create Clojure container"
                        {:gui-id gui-id :player player :platform platform-key})))
      (gui/register-active-container! clj-container)
      (gui/register-player-container! player clj-container)
      (let [menu-type (gui/get-menu-type platform-key gui-id)]
        (when-not menu-type
          (throw (ex-info "MenuType not registered"
                          {:gui-id gui-id :platform platform-key})))
        (log/info log-prefix "MenuType found, creating menu-bridge...")
        (create-menu-bridge-fn window-id menu-type clj-container)))))