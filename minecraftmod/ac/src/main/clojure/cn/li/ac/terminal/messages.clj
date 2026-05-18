(ns cn.li.ac.terminal.messages
  "Pure terminal network message identifiers shared by client GUI and server handlers.")

(def message-ids
  {:install-terminal 1000
   :install-app 1001
   :uninstall-app 1002
   :get-state 1003})

(defn msg-id
  [action]
  (or (get message-ids action)
      (throw (ex-info "Unknown terminal message action" {:action action}))))