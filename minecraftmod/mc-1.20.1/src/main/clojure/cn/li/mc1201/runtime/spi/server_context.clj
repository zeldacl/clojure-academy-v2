(ns cn.li.mc1201.runtime.spi.server-context
  "Shared server-context SPI for loader-specific runtime adapters.")

(defonce ^:private server-context-impl* (atom nil))
(defonce ^:private lifecycle-callbacks*
  (atom {:available []
         :unavailable []}))

(defn- run-callbacks!
  [callback-key value]
  (doseq [callback (get @lifecycle-callbacks* callback-key)]
    (callback value)))

(defn register-server-context-impl!
  [{:keys [get-current-server install!] :as impl}]
  (when-not (fn? get-current-server)
    (throw (ex-info "server-context SPI requires :get-current-server fn" {:impl impl})))
  (reset! server-context-impl* {:get-current-server get-current-server
                                :install! install!})
  nil)

(defn server-state
  []
  (cond
    (nil? @server-context-impl*) :unregistered
    (some? ((:get-current-server @server-context-impl*))) :available
    :else :unavailable))

(defn install-server-context!
  []
  (when-let [install! (:install! @server-context-impl*)]
    (install!))
  nil)

(defn get-current-server
  []
  (if-let [f (:get-current-server @server-context-impl*)]
    (f)
    nil))

(defn require-current-server
  []
  (or (get-current-server)
      (throw (ex-info "Server context unavailable; ensure platform runtime installed server-context SPI"
                      {:hint "Call install-server-context! during runtime init"}))))

(defn on-server-available!
  [callback]
  (when-not (fn? callback)
    (throw (ex-info "server-context available callback must be a function" {:callback callback})))
  (swap! lifecycle-callbacks* update :available conj callback)
  (when-let [server (get-current-server)]
    (callback server))
  nil)

(defn on-server-unavailable!
  [callback]
  (when-not (fn? callback)
    (throw (ex-info "server-context unavailable callback must be a function" {:callback callback})))
  (swap! lifecycle-callbacks* update :unavailable conj callback)
  nil)

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
