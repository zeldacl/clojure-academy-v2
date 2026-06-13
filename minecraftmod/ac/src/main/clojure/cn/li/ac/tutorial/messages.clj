(ns cn.li.ac.tutorial.messages
  "Pure tutorial network message identifiers shared by client GUI and server
  handlers.  Follows terminal/messages.clj pattern.")

(def message-ids
  {:tutorial/sync          1008   ;; server → client: full state
   :tutorial/request-sync  1009}) ;; client → server: request sync

(defn msg-id
  [action]
  (or (get message-ids action)
      (throw (ex-info "Unknown tutorial message action" {:action action}))))
