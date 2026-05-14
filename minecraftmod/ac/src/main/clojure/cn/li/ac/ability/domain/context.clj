(ns cn.li.ac.ability.domain.context
  "Pure context domain helpers.

  These helpers model context state transitions without touching the mutable
  context registry used by the legacy runtime."
  (:import [java.util UUID]))

(def status-constructed :constructed)
(def status-alive :alive)
(def status-terminated :terminated)

(defn valid-transition?
  [from to]
  (case [from to]
    [:constructed :alive] true
    [:constructed :terminated] true
    [:alive :terminated] true
    false))

(defn ability-context
  "Create a client-side context map."
  [player-uuid skill-id]
  {:id (str "cid-" (UUID/randomUUID))
   :server-id nil
   :player-uuid player-uuid
   :skill-id skill-id
   :status status-constructed
   :input-state :idle
   :message-buffer []
   :listeners {}
   :last-keepalive-ms nil})

(defn server-context
  "Create a server-side context map linked to a client id."
  [player-uuid skill-id client-id]
  {:id client-id
   :server-id (str "sid-" (UUID/randomUUID))
   :player-uuid player-uuid
   :skill-id skill-id
   :status status-alive
   :input-state :idle
   :message-buffer []
   :listeners {}
   :last-keepalive-ms (System/currentTimeMillis)})

(defn alive?
  [ctx]
  (= (:status ctx) status-alive))

(defn terminated?
  [ctx]
  (= (:status ctx) status-terminated))
