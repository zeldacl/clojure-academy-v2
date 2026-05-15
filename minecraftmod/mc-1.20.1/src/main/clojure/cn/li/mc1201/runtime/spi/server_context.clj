(ns cn.li.mc1201.runtime.spi.server-context
  "Shared server-context SPI for loader-specific runtime adapters.")

(defonce ^:private server-context-impl* (atom nil))

(defn register-server-context-impl!
  [{:keys [get-current-server install!] :as impl}]
  (when-not (fn? get-current-server)
    (throw (ex-info "server-context SPI requires :get-current-server fn" {:impl impl})))
  (reset! server-context-impl* {:get-current-server get-current-server
                                :install! install!})
  nil)

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
