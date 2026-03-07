(ns my-mod.gui.api
  "Abstract GUI API using multimethods")

(def ^:dynamic *forge-version* nil)

(defmulti open-gui
  "Open a GUI for a player at a position"
  (fn [_player _gui-id _world _pos] *forge-version*))

(defmulti register-menu-type
  "Register a menu/container type"
  (fn [_menu-id _factory] *forge-version*))

(defmethod open-gui :default [_ gui-id _ _]
  (throw (ex-info "No GUI implementation for version"
                  {:version *forge-version* :gui-id gui-id})))

(defmethod register-menu-type :default [menu-id _]
  (throw (ex-info "No menu registration for version"
                  {:version *forge-version* :menu-id menu-id})))
