(ns cn.li.ac.ability.client.combat-notice
  "Transient client-side combat notices rendered through the shared ability HUD."
  (:require [cn.li.mcmod.i18n :as i18n]))

(def ^:private default-duration-ms 1600)

(defn- now-ms []
  (System/currentTimeMillis))

(defn initial-notices-state
  []
  {})

(defn- format-message
  [message-key args]
  (let [template (i18n/translate message-key)]
    (if (seq args)
      (apply format template args)
      template)))

(defn- build-notice-entry
  [{:keys [text message-key args duration-ms color]} current-ms]
  (let [message-args (vec (or args []))
        message-text (or text (format-message message-key message-args))
        ttl (long (max 1 (or duration-ms default-duration-ms)))]
    {:text message-text
     :message-key message-key
     :message-args message-args
     :color (or color [255 226 120])
     :start-ms (long current-ms)
     :end-ms (+ (long current-ms) ttl)}))

(defn- update-session-notices
  [state session-id f]
  (let [next-session-state (or (f (get state session-id {})) {})]
    (if (seq next-session-state)
      (assoc state session-id next-session-state)
      (dissoc state session-id))))

(defn- clear-notice-state
  [state session-id notice-id]
  (update-session-notices state session-id #(dissoc % notice-id)))

(defn active-notice-data
  [notice current-ms]
  (when notice
    (let [now (long current-ms)]
      (when (< now (long (:end-ms notice)))
        (let [ttl (max 1.0 (double (- (:end-ms notice) (:start-ms notice))))
              remaining (max 0.0 (double (- (:end-ms notice) now)))
              alpha (min 1.0 (/ remaining ttl))]
          (assoc notice :alpha alpha))))))

(defn create-combat-notice-component
  ([]
   (create-combat-notice-component {}))
  ([{:keys [now-ms-fn]
     :or {now-ms-fn now-ms}}]
   (let [state* (atom (initial-notices-state))]
     (letfn [(snapshot []
               @state*)
             (session-snapshot [session-id]
               (get @state* session-id {}))
             (reset-state! [snapshot]
               (reset! state* (or snapshot {}))
               nil)
             (dispose! []
               (reset! state* (initial-notices-state))
               nil)
             (clear-session! [session-id]
               (swap! state* dissoc session-id)
               nil)
             (clear-notice! [session-id notice-id]
               (swap! state* clear-notice-state session-id notice-id)
               nil)
             (show-notice! [session-id notice-id notice]
               (swap! state* assoc-in [session-id notice-id]
                      (build-notice-entry notice (now-ms-fn)))
               nil)
             (active-notice [session-id notice-id current-ms]
               (let [now (long (or current-ms (now-ms-fn)))
                     notice (get-in @state* [session-id notice-id])]
                 (if-let [active (active-notice-data notice now)]
                   active
                   (do
                     (when notice
                       (swap! state* clear-notice-state session-id notice-id))
                     nil))))]
       {:kind ::combat-notice-component
        :snapshot snapshot
        :session-snapshot session-snapshot
        :reset! reset-state!
        :dispose! dispose!
        :clear-session! clear-session!
        :clear-notice! clear-notice!
        :show-notice! show-notice!
        :active-notice active-notice}))))

(defn combat-notice-component?
  [value]
  (= ::combat-notice-component (:kind value)))

(defn- require-component
  [component]
  (if (combat-notice-component? component)
    component
    (throw (ex-info "Combat notice component required"
                    {:value component}))))

(defn notices-snapshot
  [component]
  ((:snapshot (require-component component))))

(defn session-notices-snapshot
  [component session-id]
  ((:session-snapshot (require-component component)) session-id))

(defn reset-notices-for-test!
  ([component]
   (reset-notices-for-test! component {}))
  ([component snapshot]
   ((:reset! (require-component component)) snapshot)))

(defn clear-notice!
  [component session-id notice-id]
  ((:clear-notice! (require-component component)) session-id notice-id))

(defn clear-session!
  [component session-id]
  ((:clear-session! (require-component component)) session-id))

(defn show-notice!
  [component session-id notice-id notice]
  ((:show-notice! (require-component component)) session-id notice-id notice))

(defn active-notice
  [component session-id notice-id current-ms]
  ((:active-notice (require-component component)) session-id notice-id current-ms))

(defn reset-notices!
  [component]
  (reset-notices-for-test! component))

(defn dispose!
  [component]
  ((:dispose! (require-component component))))