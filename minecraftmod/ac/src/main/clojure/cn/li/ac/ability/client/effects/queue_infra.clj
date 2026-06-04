(ns cn.li.ac.ability.client.effects.queue-infra
  "Shared queue helpers for client-side effect pipelines."
  (:require [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.runtime.owner :as owner]))

(defn- require-owner-value
  [kind owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "%s queue requires %s" kind label)
                    {:owner owner
                     :required label}))))

(defn normalize-session-id
  "Resolve store partition key from a canonical client owner map or bare session token."
  [kind owner-or-session]
  (cond
    (map? owner-or-session)
    (or (owner/store-session-id owner-or-session)
        (require-owner-value kind owner-or-session
                             ":client-session-id"
                             (or (:client-session-id owner-or-session)
                                 (runtime-hooks/player-state-client-session-id)
                                 runtime-hooks/*client-session-id*)))

    (some? owner-or-session)
    owner-or-session

    :else
    (require-owner-value kind nil
                         ":client-session-id"
                         (or (runtime-hooks/player-state-client-session-id)
                             runtime-hooks/*client-session-id*))))

(defn current-effect-owner
  [kind]
  (or (runtime-hooks/current-player-state-owner)
      (when runtime-hooks/*client-session-id*
        {:logical-side :client
         :client-session-id runtime-hooks/*client-session-id*})
      (throw (ex-info (format "Current %s effect owner requires :client-session-id" kind)
                      {:required ":client-session-id"}))))

(defn queue-effect!
  [queue-atom kind owner-or-session effect-cmd]
  (swap! queue-atom update (normalize-session-id kind owner-or-session) (fnil conj []) effect-cmd)
  nil)

(defn poll-effects!
  [queue-atom kind owner-or-session]
  (let [session-id (normalize-session-id kind owner-or-session)
        drained (atom [])]
    (swap! queue-atom
           (fn [queues]
             (reset! drained (vec (get queues session-id [])))
             (dissoc queues session-id)))
    @drained))
