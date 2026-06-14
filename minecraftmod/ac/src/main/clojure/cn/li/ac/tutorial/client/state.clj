(ns cn.li.ac.tutorial.client.state
  "CLIENT-ONLY: Tutorial state cache — lightweight atom holding the player's
  tutorial activation state on the client side.

  Follows terminal/client/runtime.clj pattern but simplified since tutorial
  state is read-only on the client (activation only happens on server)."
  (:require [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.ac.tutorial.client.notification :as notification]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

;; --- State atom ---

(def ^:private default-client-state
  {:activated-tuts #{}       ; set of activated tutorial ids
   :misaka-id      nil       ; int or nil
   :first-open?    true      ; first-open animation flag
   :ready?         false})   ; true after first sync

(defonce ^:private client-state
  (atom default-client-state))

(declare apply-sync!)

;; --- Public API ---

(defn ensure-client-state!
  "Ensure the client state atom is initialized.  Idempotent."
  [_player-uuid]
  (when-not (:ready? @client-state)
    (reset! client-state default-client-state))
  nil)

(defn request-sync!
  "Send a :tutorial/request-sync message to the server.
  `owner` is the owner map used by the network layer (from client-side runtime)."
  [owner]
  (net-client/send-to-server
    owner
    (tut-msg/msg-id :tutorial/request-sync)
    {}
    (fn [response]
      (when response
        (apply-sync! response))))
  nil)

(defn apply-sync!
  "Update the client state cache from a server sync response.
  Detects newly activated tutorials and shows a toast notification
  (matching upstream NotifyUI behavior)."
  [data]
  (let [old-activated (:activated-tuts @client-state)
        new-activated (set (:activated-tuts data))
        new-ids (clojure.set/difference new-activated old-activated)]
    (swap! client-state merge
           {:activated-tuts new-activated
            :misaka-id      (:misaka-id data)
            :first-open?    (boolean (:first-open? data))
            :ready?         true})
    (log/debug "Tutorial client state synced"
               {:activated (count new-activated)
                :misaka (:misaka-id data)})
    ;; Show toast for newly activated tutorials (matching upstream NotifyUI)
    (when (and (:ready? @client-state) (seq new-ids))
      (notification/show-activation-toasts! new-ids)))
  nil)

(defn is-activated?
  "Check if a tutorial is activated from the client cache."
  [_player-uuid tut-id]
  (contains? (:activated-tuts @client-state) (keyword tut-id)))

(defn get-misaka-id
  "Get the player's Misaka ID from the client cache."
  [_player-uuid]
  (:misaka-id @client-state))

(defn first-open?
  "Return the first-open flag from the client cache."
  [_player-uuid]
  (:first-open? @client-state))

(defn ready?
  "True after at least one successful sync."
  []
  (:ready? @client-state))

;; --- Testing ---

(defn reset-state!
  "Reset client state to default.  For test isolation only."
  []
  (reset! client-state default-client-state)
  nil)
