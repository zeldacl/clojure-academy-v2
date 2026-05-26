(ns cn.li.ac.wireless.gui.message.registry
  (:require [cn.li.mcmod.gui.message.dsl :as msg-dsl]))

(def ^:private registry (atom {}))
(def ^:private registry-frozen? (atom false))

(def ^:private message-prefix "wireless")

(defn- assert-registry-open!
  []
  (when @registry-frozen?
    (throw (ex-info "Wireless GUI message registry is frozen" {}))))

(defn registry-snapshot []
  {:domains @registry
   :frozen? @registry-frozen?})

(defn reset-registry-for-test!
  ([]
   (reset-registry-for-test! {}))
  ([{:keys [domains frozen?]
     :or {domains {} frozen? false}}]
   (reset! registry domains)
   (reset! registry-frozen? frozen?)
   nil))

(defn freeze-registry!
  []
  (reset! registry-frozen? true)
  nil)

(defn register-block-messages!
  [domain actions]
  (let [spec (msg-dsl/build-domain-spec message-prefix domain actions)]
    (if-let [existing (get @registry domain)]
      (when-not (= existing spec)
        (throw (ex-info "Conflicting wireless GUI message domain"
                        {:domain domain :existing existing :new spec})))
      (do
        (assert-registry-open!)
        (swap! registry assoc domain spec)))
    spec))

(defn get-domain-spec [domain]
  (get @registry domain))

(defn build-catalog []
  (msg-dsl/build-catalog (vals @registry)))

(defn msg [domain action]
  (msg-dsl/msg-id (build-catalog) domain action))
