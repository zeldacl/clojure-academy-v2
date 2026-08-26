(ns cn.li.mcmod.runtime.host)
(def ^:const schema-version 1)
(defn create [{:keys [queries actions] :as host}]
  (when-not (map? host) (throw (ex-info "host must be a map" {:host host})))
  (doseq [[kind table] [[:queries queries] [:actions actions]]]
    (when-not (map? table) (throw (ex-info "host table must be a map" {:kind kind :value table})))
    (when-let [bad (seq (remove (comp ifn? val) table))]
      (throw (ex-info "host table contains a non-callable handler" {:kind kind :capabilities (mapv key bad)}))))
  {:schema-version schema-version :queries (or queries {}) :actions (or actions {})})
(defn query!
  ([host capability request]
   (query! host capability request nil))
  ([host capability request context]
  (let [handler (get-in host [:queries capability])]
    (when-not (ifn? handler) (throw (ex-info "missing host query capability" {:capability capability})))
    (if (nil? context)
      (handler request)
      (try
        (handler request context)
        (catch clojure.lang.ArityException _
          (handler request)))))))
(defn- command-id [command index] (or (:id command) [:command index]))
(defn- check-command [handler command index context]
  (try
    (let [result (handler :preflight command context)]
      (if (or (= true result) (and (map? result) (:ok? result))) {:ok? true}
        {:ok? false :reason :host-preflight-rejected :command-id (command-id command index) :capability (:capability command) :detail result}))
    (catch Throwable error {:ok? false :reason :host-preflight-error :command-id (command-id command index) :capability (:capability command) :message (.getMessage ^Throwable error)})))
(defn preflight! [host commands context]
  (let [commands (vec commands)]
    (loop [[entry & more] (map-indexed (fn [index command] [index command]) commands)]
      (if-not entry {:ok? true :commands commands}
        (let [[index command] entry handler (get-in host [:actions (:capability command)])]
          (if-not (ifn? handler) {:ok? false :reason :missing-host-action :command-id (command-id command index) :capability (:capability command)}
            (let [result (check-command handler command index context)]
              (if (:ok? result) (recur more) result))))))))
(defn- apply-command [handler command context]
  (try {:ok? true :value (handler :apply command context)}
       (catch Throwable error {:ok? false :error error})))
(defn apply! [host commands context]
  (loop [remaining (vec commands) applied [] results []]
    (if-let [command (first remaining)]
      (let [handler (get-in host [:actions (:capability command)]) attempt (apply-command handler command context)]
        (if-not (:ok? attempt)
          {:ok? false :reason :host-apply-error :applied-ids applied :failed-id (:id command) :message (.getMessage ^Throwable (:error attempt))}
          (let [result (:value attempt)]
            (if (or (= true result)
                    (and (map? result)
                         (not (contains? #{:failed :rejected :unhandled}
                                         (:status result)))))
              (recur (subvec remaining 1)
                     (conj applied (:id command))
                     (conj results {:id (:id command)
                                    :capability (:capability command)
                                    :value result}))
              {:ok? false :reason :host-apply-rejected :applied-ids applied :failed-id (:id command) :detail result}))))
      {:ok? true :applied-ids applied :results results})))
(defn execute! [host commands context]
  (let [preflight (preflight! host commands context)]
    (if-not (:ok? preflight) {:ok? false :phase :preflight :error preflight}
      (let [applied (apply! host commands context)]
        (if (:ok? applied) {:ok? true :phase :apply :applied-ids (:applied-ids applied)
                            :results (:results applied)}
          {:ok? false :phase :apply :error applied})))))
