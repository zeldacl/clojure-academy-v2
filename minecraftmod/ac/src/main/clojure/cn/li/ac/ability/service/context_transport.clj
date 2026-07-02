(ns cn.li.ac.ability.service.context-transport
  "Transport port for ability context server/client messaging.

  Owns send-function registration and provides transport operations used by
  lifecycle/runtime services.

  State stored in Framework [:service :context-transport]."
  (:require [cn.li.mcmod.framework :as fw]))

(defn default-context-transport-runtime-state []
  {:to-client nil :to-server nil :frozen? false})

(def ^:private ct-path [:service :context-transport])

(defn- context-transport-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom ct-path)
        (let [a (atom (default-context-transport-runtime-state))]
          (swap! fw-atom assoc-in ct-path a) a))
    (atom (default-context-transport-runtime-state))))

(defn- context-transport-state-snapshot [] @(context-transport-state-atom))
(defn- update-context-transport-state! [f & args] (apply swap! (context-transport-state-atom) f args))
(defn- assert-transport-open! [] (when (:frozen? (context-transport-state-snapshot)) (throw (ex-info "Context transport is frozen" {}))))

(defn context-transport-snapshot [] (context-transport-state-snapshot))

(defn reset-context-transport-for-test!
  ([] (reset-context-transport-for-test! {}))
  ([{:keys [to-client to-server frozen?] :or {to-client nil to-server nil frozen? false}}]
   (reset! (context-transport-state-atom) {:to-client to-client :to-server to-server :frozen? frozen?}) nil))

(defn freeze-context-transport! [] (update-context-transport-state! assoc :frozen? true) nil)

(defn register-send-fns! [{:keys [to-client to-server]}]
  (assert-transport-open!)
  (update-context-transport-state! merge {:to-client to-client :to-server to-server}) nil)

(defn send-to-client! [player-uuid msg-id payload]
  (when-let [f (:to-client (context-transport-state-snapshot))] (f player-uuid msg-id payload)))

(defn send-to-server! [msg-id payload]
  (when-let [f (:to-server (context-transport-state-snapshot))] (f msg-id payload)))
