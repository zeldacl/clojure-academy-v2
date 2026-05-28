(ns cn.li.mc1201.runtime.spi.server-context
  "Shared server-context SPI for loader-specific runtime adapters.")

(def ^:private server-context-state-lock
  (Object.))

(def ^:private ^:dynamic *server-context-impl* nil)

(def ^:private ^:dynamic *lifecycle-callbacks*
  {:available []
   :unavailable []})

(defn- server-context-impl
  []
  (var-get #'*server-context-impl*))

(defn- lifecycle-callbacks
  []
  (var-get #'*lifecycle-callbacks*))

(defn- update-lifecycle-callbacks!
  [update-fn]
  (locking server-context-state-lock
    (let [updated (update-fn (lifecycle-callbacks))]
      (alter-var-root #'*lifecycle-callbacks* (constantly updated))
      updated)))

(defn reset-server-context-spi-for-test!
  []
  (locking server-context-state-lock
    (alter-var-root #'*server-context-impl* (constantly nil))
    (alter-var-root #'*lifecycle-callbacks* (constantly {:available []
                                                         :unavailable []})))
  nil)

(defn- register-callback!
  [callback-key callback-id callback]
  (let [added? (volatile! false)]
    (update-lifecycle-callbacks!
      (fn [callbacks-by-key]
        (update callbacks-by-key
                callback-key
                (fn [callbacks]
                  (let [callbacks (vec callbacks)]
                    (if (some #(= (:id %) callback-id) callbacks)
                      callbacks
                      (do
                        (vreset! added? true)
                        (conj callbacks {:id callback-id
                                         :callback callback}))))))))
    @added?))

(defn- run-callbacks!
  [callback-key value]
  (doseq [{:keys [callback]} (get (lifecycle-callbacks) callback-key)]
    (callback value)))

(defn register-server-context-impl!
  [{:keys [get-current-server install!] :as impl}]
  (when-not (fn? get-current-server)
    (throw (ex-info "server-context SPI requires :get-current-server fn" {:impl impl})))
  (locking server-context-state-lock
    (alter-var-root #'*server-context-impl* (constantly {:get-current-server get-current-server
                                                         :install! install!})))
  nil)

(defn server-state
  []
  (cond
    (nil? (server-context-impl)) :unregistered
    (some? ((:get-current-server (server-context-impl)))) :available
    :else :unavailable))

(defn install-server-context!
  []
  (when-let [install! (:install! (server-context-impl))]
    (install!))
  nil)

(defn get-current-server
  []
  (if-let [f (:get-current-server (server-context-impl))]
    (f)
    nil))

(defn require-current-server
  []
  (or (get-current-server)
      (throw (ex-info "Server context unavailable; ensure platform runtime installed server-context SPI"
                      {:hint "Call install-server-context! during runtime init"}))))

(defn on-server-available!
  ([callback]
   (on-server-available! callback callback))
  ([callback-id callback]
   (when-not (fn? callback)
     (throw (ex-info "server-context available callback must be a function" {:callback callback})))
   (when (register-callback! :available callback-id callback)
     (when-let [server (get-current-server)]
       (callback server)))
   nil))

(defn on-server-unavailable!
  ([callback]
   (on-server-unavailable! callback callback))
  ([callback-id callback]
   (when-not (fn? callback)
     (throw (ex-info "server-context unavailable callback must be a function" {:callback callback})))
   (register-callback! :unavailable callback-id callback)
   nil))

(defn notify-server-available!
  [server]
  (run-callbacks! :available server)
  nil)

(defn notify-server-unavailable!
  ([]
   (notify-server-unavailable! nil))
  ([server]
   (run-callbacks! :unavailable server)
   nil))
