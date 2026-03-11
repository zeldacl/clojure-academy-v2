(ns my-mod.wireless.gui.wireless-messages
  "Aggregated wireless GUI message catalog for validation and lookup."
  (:require [my-mod.wireless.gui.messages-dsl :as msg-dsl]
            [my-mod.wireless.gui.node-messages :as node-msgs]
            [my-mod.wireless.gui.matrix-messages :as matrix-msgs]))

(def catalog
  (msg-dsl/build-catalog
    [node-msgs/node-domain-spec
     matrix-msgs/matrix-domain-spec]))

(defn msg
  [domain action]
  (msg-dsl/msg-id catalog domain action))

(defn find-by-msg-id
  [message-id]
  (msg-dsl/find-by-msg-id catalog message-id))
