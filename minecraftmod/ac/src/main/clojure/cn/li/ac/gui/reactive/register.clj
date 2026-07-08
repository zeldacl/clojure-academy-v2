(ns cn.li.ac.gui.reactive.register
  "Reactive GUI bridge — installs :open-reactive-screen handler via client bridge merge.
   Individual block GUIs self-register via their own init-*-reactive! functions."
  (:require [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]))

(def ^:private screen-creators (atom {}))

(defn- reactive-screen-handler
  [gui-key container menu player]
  (if-let [entry (get @screen-creators gui-key)]
    ((:create entry) container menu player)
    (do (log/warn "No reactive screen for:" gui-key) nil)))

(defn register-screen!
  "Called by each gui_reactive.clj's init-*-reactive! to register its screen creator."
  [gui-key create-fn title]
  (swap! screen-creators assoc gui-key {:create create-fn :title title}))

(defn install-bridge!
  "Install reactive screen handler into client bridge via merge."
  []
  (bridge/merge-client-bridge! {:open-reactive-screen reactive-screen-handler})
  (log/info "Reactive screen bridge installed"))
