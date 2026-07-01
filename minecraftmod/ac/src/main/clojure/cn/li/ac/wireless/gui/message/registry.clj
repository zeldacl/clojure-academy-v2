(ns cn.li.ac.wireless.gui.message.registry
  (:require [cn.li.mcmod.gui.message.dsl :as msg-dsl]
            [cn.li.mcmod.gui.registry-contract :as registry-contract]))

(defn default-wireless-message-registry-runtime-state
  []
  {:domains {}
   :frozen? false})

(defn create-wireless-message-registry-runtime
  ([] (create-wireless-message-registry-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-wireless-message-registry-runtime-state))}}]
   {::runtime ::wireless-message-registry-runtime
    :state* state*}))

(def ^:private _wireless-message-registry-runtime (delay (create-wireless-message-registry-runtime)))

(def ^:dynamic *wireless-message-registry-runtime* nil)

(defn call-with-wireless-message-registry-runtime
  [runtime f]
  (when-not (and (map? runtime)
                 (= ::wireless-message-registry-runtime (::runtime runtime))
                 (some? (:state* runtime)))
    (throw (ex-info "Expected wireless message registry runtime" {:runtime runtime})))
  (binding [*wireless-message-registry-runtime* runtime]
    (f)))

(defn- current-wireless-message-registry-runtime
  []
  (or *wireless-message-registry-runtime*
      @_wireless-message-registry-runtime))

(defn- wireless-message-registry-state-atom
  []
  (:state* (current-wireless-message-registry-runtime)))

(defn- wireless-message-registry-state-snapshot
  []
  @(wireless-message-registry-state-atom))

(defn- update-wireless-message-registry-state!
  [f & args]
  (apply swap! (wireless-message-registry-state-atom) f args))

(def ^:private message-prefix "wireless")

(defn- assert-registry-open!
  []
  (when (:frozen? (wireless-message-registry-state-snapshot))
    (throw (ex-info "Wireless GUI message registry is frozen" {}))))

(defn registry-snapshot []
  (wireless-message-registry-state-snapshot))

(defn reset-registry-for-test!
  ([] (reset-registry-for-test! {}))
  ([{:keys [domains frozen?]
     :or {domains {} frozen? false}}]
   (reset! (wireless-message-registry-state-atom)
           {:domains domains
            :frozen? frozen?})
   nil))

(defn freeze-registry!
  []
  (update-wireless-message-registry-state! assoc :frozen? true)
  nil)

(defn register-block-messages!
  ([domain actions]
   (register-block-messages! domain actions (registry-contract/default-server-gui-handler-contract)))
  ([domain actions contract]
   (let [contract* (registry-contract/normalize-message-domain-contract domain contract)
         spec (msg-dsl/build-domain-spec message-prefix domain actions contract*)]
     (if-let [existing (get (:domains (wireless-message-registry-state-snapshot)) domain)]
       (when-not (= existing spec)
         (throw (ex-info "Conflicting wireless GUI message domain"
                         {:domain domain :existing existing :new spec})))
       (do
         (assert-registry-open!)
         (update-wireless-message-registry-state! assoc-in [:domains domain] spec)))
     spec)))

(defn get-domain-spec [domain]
  (get (:domains (wireless-message-registry-state-snapshot)) domain))

(defn build-catalog []
  (msg-dsl/build-catalog (vals (:domains (wireless-message-registry-state-snapshot)))))

(defn msg
  "Resolve a message ID for the given domain+action.
   Uses direct ID computation (matching build-domain-spec formula)
   so callers don't depend on catalog being populated first.
   Format: <prefix>_<domain>_<action> with underscores for hyphens."
  [domain action]
  (msg-dsl/message-id message-prefix domain action))
