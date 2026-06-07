(ns cn.li.mcmod.gui.owner-contract
  "GUI-specific owner contract wrappers; canonical validation lives in runtime.owner."
  (:require [cn.li.mcmod.runtime.owner :as runtime-owner]
            [cn.li.mcmod.schema.core :as schema]))

(def ^:private message-envelope-schema
  [:map
   [:msg-id string?]
   [:payload map?]
   [:request-id {:optional true} int?]])

(def ^:private sync-routing-schema
  [:map
   [:container-id int?]])

(def ^:private valid-message-envelope* (schema/validator message-envelope-schema))
(def ^:private valid-sync-routing* (schema/validator sync-routing-schema))

(defn valid-client-owner? [owner]
  (runtime-owner/valid-client-owner? owner))

(defn valid-server-owner? [owner]
  (runtime-owner/valid-server-owner? owner))

(defn valid-owner? [owner]
  (runtime-owner/valid-owner? owner))

(defn explain-owner [owner]
  (runtime-owner/explain-owner owner))

(defn require-client-owner [owner]
  (runtime-owner/require-client-owner owner))

(defn require-server-owner [owner]
  (runtime-owner/require-server-owner owner))

(defn require-owner [owner]
  (runtime-owner/require-owner owner))

(defn require-message-envelope
  [envelope]
  (if (schema/valid? valid-message-envelope* envelope)
    envelope
    (throw (ex-info "message-envelope contract violation"
                    {:contract :message-envelope
                     :value envelope
                     :explain (schema/explain message-envelope-schema envelope)}))))

(defn require-sync-routing
  [routing]
  (if (schema/valid? valid-sync-routing* routing)
    routing
    (throw (ex-info "sync-routing contract violation"
                    {:contract :sync-routing
                     :value routing
                     :explain (schema/explain sync-routing-schema routing)}))))
