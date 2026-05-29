(ns cn.li.mcmod.hooks.messages
  "Platform-neutral runtime message-id registry.

  Business/content modules own concrete wire message ids and register them by
  generic keys. Shared Minecraft/platform code resolves ids through this seam
  without carrying business protocol strings.")

(defn create-message-id-registry-runtime
  ([] (create-message-id-registry-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.hooks.messages/runtime ::message-id-registry-runtime
    :state* (or state* (atom {}))}))

(defonce ^:private installed-message-id-registry-runtime
  (create-message-id-registry-runtime))

(defn- message-ids-atom []
  (:state* installed-message-id-registry-runtime))

(defn- message-ids-snapshot []
  @(message-ids-atom))

(defn register-message!
  "Register a concrete wire message id for `message-key`."
  [message-key message-id]
  (when-not (keyword? message-key)
    (throw (ex-info "Message key must be a keyword"
                    {:message-key message-key})))
  (when-not (and (string? message-id) (not (empty? message-id)))
    (throw (ex-info "Message id must be a non-empty string"
                    {:message-key message-key :message-id message-id})))
  (swap! (message-ids-atom) assoc message-key message-id)
  nil)

(defn register-messages!
  "Register a map of message-key → concrete wire message id."
  [messages]
  (doseq [[message-key message-id] messages]
    (register-message! message-key message-id))
  nil)

(defn maybe-msg-id
  "Return the registered wire message id for `message-key`, or nil."
  [message-key]
  (get (message-ids-snapshot) message-key))

(defn msg-id
  "Return the registered wire message id for `message-key`.

  Throws when no id has been installed, because sending nil as a network id is
  always a wiring bug."
  [message-key]
  (or (maybe-msg-id message-key)
      (throw (ex-info "Runtime message id is not registered"
                      {:message-key message-key}))))

(defn valid-msg-id?
  "Return true when `message-id` is one of the registered wire ids."
  [message-id]
  (contains? (set (vals (message-ids-snapshot))) message-id))

(defn clear-messages!
  "Clear registered message ids. Intended for tests."
  []
  (reset! (message-ids-atom) {})
  nil)