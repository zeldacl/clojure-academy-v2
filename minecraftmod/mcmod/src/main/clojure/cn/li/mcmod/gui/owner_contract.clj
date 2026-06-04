(ns cn.li.mcmod.gui.owner-contract
  "GUI-specific owner contract wrappers; canonical validation lives in runtime.owner."
  (:require [clojure.spec.alpha :as s]
            [cn.li.mcmod.runtime.owner :as runtime-owner]))

(s/def ::msg-id string?)
(s/def ::payload map?)
(s/def ::request-id int?)

(s/def ::message-envelope
  (s/keys :req-un [::msg-id ::payload]
          :opt-un [::request-id]))

(s/def ::container-id int?)
(s/def ::pos-x number?)
(s/def ::pos-y number?)
(s/def ::pos-z number?)

(s/def ::sync-routing
  (s/and
   map?
   (fn [m]
     (or (integer? (:container-id m))
         (and (number? (:pos-x m))
              (number? (:pos-y m))
              (number? (:pos-z m)))))))

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
  (if (s/valid? ::message-envelope envelope)
    envelope
    (throw (ex-info "message-envelope contract violation"
                    {:contract :message-envelope
                     :value envelope
                     :explain (s/explain-data ::message-envelope envelope)}))))

(defn require-sync-routing
  [routing]
  (if (s/valid? ::sync-routing routing)
    routing
    (throw (ex-info "sync-routing contract violation"
                    {:contract :sync-routing
                     :value routing
                     :explain (s/explain-data ::sync-routing routing)}))))
