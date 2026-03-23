(ns cn.li.ac.wireless.gui.matrix-messages
  "Matrix message IDs - minimal stub for wireless-messages compatibility."
  (:require [cn.li.ac.wireless.gui.messages-dsl :as msg-dsl]))

(def matrix-actions
  [:gather-info :init :change-ssid :change-password])

(def matrix-domain-spec
  (msg-dsl/build-domain-spec :matrix matrix-actions))
