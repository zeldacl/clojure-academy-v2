(ns cn.li.mc1201.runtime.spi.network-transport
  "Shared network transport SPI for runtime messaging.")

(defonce ^:private transport-impl* (atom nil))

(defn register-transport-impl!
  [{:keys [send-to-server! send-push-to-client! find-player-by-uuid find-nearby-player-uuids]
    :or {find-nearby-player-uuids (fn [_source-player-uuid _radius] [])}
    :as impl}]
  (doseq [[k v] [[:send-to-server! send-to-server!]
                 [:send-push-to-client! send-push-to-client!]
                 [:find-player-by-uuid find-player-by-uuid]]]
    (when-not (fn? v)
      (throw (ex-info (str "network transport SPI requires " k " fn") {:impl impl :missing k}))))
  (reset! transport-impl* {:send-to-server! send-to-server!
                           :send-push-to-client! send-push-to-client!
                   :find-player-by-uuid find-player-by-uuid
                   :find-nearby-player-uuids find-nearby-player-uuids})
  nil)

(defn transport-impl!
  []
  (or @transport-impl*
      (throw (ex-info "Network transport SPI not installed"
                      {:hint "Call register-transport-impl! from platform runtime network init"}))))

(defn send-to-server!
  [msg-id payload]
  ((:send-to-server! (transport-impl!)) msg-id payload))

(defn send-push-to-client!
  [player msg-id payload]
  ((:send-push-to-client! (transport-impl!)) player msg-id payload))

(defn find-player-by-uuid
  [uuid-str]
  ((:find-player-by-uuid (transport-impl!)) uuid-str))

(defn find-nearby-player-uuids
  [source-player-uuid radius]
  ((:find-nearby-player-uuids (transport-impl!)) source-player-uuid radius))
