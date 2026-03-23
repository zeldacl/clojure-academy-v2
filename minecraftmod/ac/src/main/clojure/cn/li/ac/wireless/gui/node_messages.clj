(ns cn.li.ac.wireless.gui.node-messages
  "Node message IDs - minimal stub for wireless-messages compatibility."
  (:require [cn.li.ac.wireless.gui.messages-dsl :as msg-dsl]))

(def node-actions
  [:get-status :change-name :change-password :list-networks :connect :disconnect])

(def node-domain-spec
  (msg-dsl/build-domain-spec :node node-actions))
