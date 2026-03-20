(ns cn.li.ac.wireless.gui.matrix-messages
  "Single source of truth for wireless matrix GUI messages."
  (:require [cn.li.ac.wireless.gui.messages-dsl :as msg-dsl]))

(def matrix-actions
  [:gather-info
   :init
   :change-ssid
   :change-password])

(def matrix-domain-spec
  (msg-dsl/build-domain-spec :matrix matrix-actions))

(defn msg
  [action]
  (or (get-in matrix-domain-spec [:messages action])
      (throw (ex-info "Unknown matrix message action"
                      {:action action}))))
