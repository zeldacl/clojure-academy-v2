(ns cn.li.ac.ability.client.combat-notice
  "Transient client-side combat notices rendered through the shared ability HUD."
  (:require [cn.li.mcmod.i18n :as i18n]))

(def ^:private default-duration-ms 1600)

(defonce ^:private notices* (atom {}))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- format-message
  [message-key args]
  (let [template (i18n/translate message-key)]
    (if (seq args)
      (apply format template args)
      template)))

(defn show-notice!
  [notice-id {:keys [text message-key args duration-ms color]}]
  (let [message-text (or text (format-message message-key args))
        start (now-ms)
        ttl (long (max 1 (or duration-ms default-duration-ms)))]
    (swap! notices* assoc notice-id {:text message-text
                                     :message-key message-key
                                     :message-args (vec (or args []))
                                     :color (or color [255 226 120])
                                     :start-ms start
                                     :end-ms (+ start ttl)}))
  nil)

(defn active-notice
  [notice-id current-ms]
  (let [now (long (or current-ms (now-ms)))
        notice (get @notices* notice-id)]
    (when notice
      (if (>= now (long (:end-ms notice)))
        (do
          (swap! notices* dissoc notice-id)
          nil)
        (let [ttl (max 1.0 (double (- (:end-ms notice) (:start-ms notice))))
              remaining (max 0.0 (double (- (:end-ms notice) now)))
              alpha (min 1.0 (/ remaining ttl))]
          (assoc notice :alpha alpha))))))

(defn reset-notices!
  []
  (reset! notices* {})
  nil)