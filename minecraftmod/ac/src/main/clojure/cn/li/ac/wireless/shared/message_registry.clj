(ns cn.li.ac.wireless.shared.message-registry
  "Centralized wireless message registration.

  This provides a single startup registration path while legacy namespaces
  are still migrating."
  (:require [cn.li.ac.wireless.gui.message.registry :as msg-registry]))

(def ^:private default-domain-actions
  {:matrix    [:gather-info :init :change-ssid :change-password]
   :node      [:get-status :change-name :change-password :list-networks :connect :disconnect]
   :generator [:get-status :list-nodes :connect :disconnect]})

(defn register-all!
  []
  (doseq [[domain actions] default-domain-actions]
    (msg-registry/register-block-messages! domain actions))
  true)
