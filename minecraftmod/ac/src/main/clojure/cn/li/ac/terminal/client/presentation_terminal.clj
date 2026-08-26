(ns cn.li.ac.terminal.client.presentation-terminal
  "Terminal surface controller backed by Presentation Runtime v2."
  (:require [cn.li.ac.terminal.client.runtime :as terminal]
            [cn.li.ac.gui.presentation-v2 :as v2]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def binding-ids {:installed? 0 :apps 1 :page 2 :loading? 3 :query 4 :modal 5})
(def action-ids {0 :terminal/set-page 1 :terminal/query
                 2 :terminal/install-app 3 :terminal/uninstall-app
                 4 :terminal/submit-query 5 :terminal/close-modal})

(defn terminal-view-model [owner dispatch-action!]
  (let [state (terminal/state-snapshot owner)
        vm (v2/mount-view!
             {:view-id :academy/app/terminal
              :host-kind :screen
              :state state
              :dispatch-action!
              (fn [action payload _current]
                (dispatch-action! action payload)
                (terminal/state-snapshot owner))})]
    (assoc vm
           :refresh! (fn []
                       (v2/present! vm (terminal/state-snapshot owner)))
           :state (:state vm))))

(defn mount-terminal! [owner dispatch-action!]
  (terminal/ensure-owner! owner)
  (terminal-view-model owner dispatch-action!))

(defn open-screen! [owner dispatch-action! on-close]
  (let [vm (mount-terminal! owner dispatch-action!)]
    (client-bridge/call-adapter :presentation-open-screen!
                                 (:mount vm) "Terminal" on-close)
    vm))