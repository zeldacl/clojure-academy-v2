(ns cn.li.ac.wireless.gui.wireless-messages
  "Aggregated wireless GUI message catalog for validation and lookup."
  (:require [cn.li.ac.wireless.gui.messages-dsl :as msg-dsl]
            [cn.li.ac.wireless.gui.generator-messages :as gen-msgs]
            [cn.li.ac.wireless.gui.node-messages :as node-msgs]
            [cn.li.ac.wireless.gui.matrix-messages :as matrix-msgs]))

(def catalog
  (msg-dsl/build-catalog
    [gen-msgs/generator-domain-spec
     node-msgs/node-domain-spec
     matrix-msgs/matrix-domain-spec]))

(defn msg
  [domain action]
  (msg-dsl/msg-id catalog domain action))

(defn find-by-msg-id
  [message-id]
  (msg-dsl/find-by-msg-id catalog message-id))
