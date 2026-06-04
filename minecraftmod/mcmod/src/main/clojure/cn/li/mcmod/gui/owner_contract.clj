(ns cn.li.mcmod.gui.owner-contract
  "Canonical GUI owner and message envelope contracts (clojure.spec + fail-fast validators)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::logical-side #{:client :server})
(s/def ::player-uuid string?)
(s/def ::session-token (s/or :keyword keyword? :vector vector? :symbol symbol?))
(s/def ::client-session-id ::session-token)
(s/def ::server-session-id ::session-token)

(s/def ::client-owner
  (s/and
   (s/keys :req-un [::client-session-id]
           :opt-un [::player-uuid ::logical-side ::screen-id ::channel-id ::timeout-ms
                   ::client-network-session])
   (fn [m]
     (and (nil? (:server-session-id m))
          (or (nil? (:logical-side m))
              (= :client (:logical-side m)))))))

(s/def ::server-owner
  (s/and
   (s/keys :req-un [::server-session-id ::player-uuid]
           :opt-un [::logical-side])
   (fn [m]
     (and (nil? (:client-session-id m))
          (or (nil? (:logical-side m))
              (= :server (:logical-side m)))))))

(s/def ::owner (s/or :client ::client-owner :server ::server-owner))

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

(defn- normalize-player-uuid
  [owner]
  (when (map? owner)
    (let [uuid (some-> (:player-uuid owner) str)]
      (when (not (str/blank? uuid))
        (assoc owner :player-uuid uuid)))))

(defn- contract-ex-info
  [contract value explain]
  (ex-info (str contract " contract violation")
           {:contract contract
            :value value
            :explain explain}))

(defn- require*
  [spec contract value]
  (let [value* (or (normalize-player-uuid value) value)]
    (if (s/valid? spec value*)
      value*
      (throw (contract-ex-info contract value* (s/explain-data spec value*))))))

(defn- require-player-uuid!
  [contract owner]
  (when (str/blank? (:player-uuid owner))
    (throw (ex-info (str contract " requires :player-uuid")
                    {:contract contract
                     :value owner
                     :required :player-uuid})))
  owner)

(defn valid-client-owner?
  [owner]
  (s/valid? ::client-owner (normalize-player-uuid owner)))

(defn valid-server-owner?
  [owner]
  (s/valid? ::server-owner (normalize-player-uuid owner)))

(defn valid-owner?
  [owner]
  (s/valid? ::owner (normalize-player-uuid owner)))

(defn explain-owner
  [owner]
  (s/explain-data ::owner (normalize-player-uuid owner)))

(defn require-client-owner
  "Require canonical client owner with :client-session-id and :player-uuid."
  [owner]
  (require-player-uuid! :client-owner
                        (require* ::client-owner :client-owner owner)))

(defn require-server-owner
  [owner]
  (require* ::server-owner :server-owner owner))

(defn require-owner
  [owner]
  (require* ::owner :owner owner))

(defn require-message-envelope
  [envelope]
  (require* ::message-envelope :message-envelope envelope))

(defn require-sync-routing
  [routing]
  (require* ::sync-routing :sync-routing routing))
