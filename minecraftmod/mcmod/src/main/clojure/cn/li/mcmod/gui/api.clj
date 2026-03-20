(ns cn.li.mcmod.gui.api
  "Abstract GUI API using multimethods")

(require '[cn.li.mcmod.platform.dispatch :as platform-dispatch])

(defmulti open-gui
  "Open a GUI for a player at a position"
  (fn [_player _gui-id _world _pos] platform-dispatch/*platform-version*))

(defmulti register-menu-type
  "Register a menu/container type"
  (fn [_menu-id _factory] platform-dispatch/*platform-version*))

(defmethod open-gui :default [_ gui-id _ _]
  (throw (ex-info "No GUI implementation for version"
                  {:version platform-dispatch/*platform-version* :gui-id gui-id})))

(defmethod register-menu-type :default [menu-id _]
  (throw (ex-info "No menu registration for version"
                  {:version platform-dispatch/*platform-version* :menu-id menu-id})))
