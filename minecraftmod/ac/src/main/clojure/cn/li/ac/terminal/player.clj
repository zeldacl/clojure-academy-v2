(ns cn.li.ac.terminal.player
  "Session-scoped terminal state in the ability runtime store."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.terminal.model :as model]
            [cn.li.mcmod.util.log :as log]))

(defn state
  [session-id uuid-str]
  (let [player-state (store/get-player-state* session-id uuid-str)]
    (or (get player-state model/state-key)
        (model/fresh-state))))

(defn update-state!
  [session-id uuid-str f & args]
  (store/update-player-state!* session-id
                               uuid-str
                               #(assoc % model/state-key
                                       (apply f (or (get % model/state-key)
                                                      (model/fresh-state))
                                              args)))
  (store/mark-player-dirty! session-id uuid-str))

(defn terminal-installed?
  [session-id uuid-str]
  (model/terminal-installed? (state session-id uuid-str)))

(defn installed-apps
  [session-id uuid-str]
  (:installed-apps (model/normalize-state (state session-id uuid-str))))

(defn app-installed?
  [session-id uuid-str app-id]
  (model/app-installed? (state session-id uuid-str) app-id))

(defn install-terminal!
  [session-id uuid-str]
  (log/info "Installing terminal for player:" uuid-str)
  (update-state! session-id uuid-str model/install-terminal))

(defn uninstall-terminal!
  [session-id uuid-str]
  (log/info "Uninstalling terminal for player:" uuid-str)
  (update-state! session-id uuid-str model/uninstall-terminal))

(defn install-app!
  [session-id uuid-str app-id]
  (log/info "Installing app" app-id "for player:" uuid-str)
  (update-state! session-id uuid-str model/install-app app-id))

(defn uninstall-app!
  [session-id uuid-str app-id]
  (log/info "Uninstalling app" app-id "for player:" uuid-str)
  (update-state! session-id uuid-str model/uninstall-app app-id))

(defn install-apps!
  [session-id uuid-str app-ids]
  (log/info "Installing apps" app-ids "for player:" uuid-str)
  (update-state! session-id uuid-str model/install-apps app-ids))

(defn ensure-state!
  [session-id uuid-str]
  (when-not (get (store/get-player-state* session-id uuid-str) model/state-key)
    (store/update-player-state!* session-id
                                 uuid-str
                                 #(assoc % model/state-key (model/fresh-state)))))
