(ns cn.li.ac.wireless.gui.generator-messages
  "Single source of truth for wireless generator GUI messages (SolarGen etc.)."
  (:require [cn.li.ac.wireless.gui.messages-dsl :as msg-dsl]))

(def generator-actions
  [:get-status
   :list-nodes
   :connect
   :disconnect])

(def generator-domain-spec
  (msg-dsl/build-domain-spec :generator generator-actions))

(defn msg
  [action]
  (or (get-in generator-domain-spec [:messages action])
      (throw (ex-info "Unknown generator message action"
                      {:action action}))))

