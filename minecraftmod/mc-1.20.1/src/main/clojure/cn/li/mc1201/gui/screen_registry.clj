(ns cn.li.mc1201.gui.screen-registry
  "Shared registration loop for platform GUI screen factories.")

(defn register-all-screens!
  [platform get-all-gui-ids-fn get-menu-type-fn get-screen-factory-fn-kw-fn register-screen-fn]
  (doseq [gui-id (get-all-gui-ids-fn)]
    (let [menu-type (get-menu-type-fn platform gui-id)
          factory-fn-kw (get-screen-factory-fn-kw-fn gui-id)]
      (register-screen-fn gui-id menu-type factory-fn-kw))))