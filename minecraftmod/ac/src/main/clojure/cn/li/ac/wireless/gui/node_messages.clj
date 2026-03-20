(ns cn.li.ac.wireless.gui.node-messages
  "Single source of truth for wireless node GUI messages."
  (:require [cn.li.ac.wireless.gui.messages-dsl :as msg-dsl]))

(def node-actions
  [:get-status
   :change-name
   :change-password
   :list-networks
   :connect
   :disconnect])

(def node-domain-spec
  (msg-dsl/build-domain-spec :node node-actions))

(defn msg
  [action]
  (or (get-in node-domain-spec [:messages action])
      (throw (ex-info "Unknown node message action"
                      {:action action}))))
