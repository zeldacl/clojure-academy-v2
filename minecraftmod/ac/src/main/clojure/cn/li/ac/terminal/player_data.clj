(ns cn.li.ac.terminal.player-data
  "Terminal data extension for player state.

  Extends the existing player-state atom with terminal installation tracking
  and app management. Reuses the existing dirty tracking and sync mechanisms."
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
[cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Terminal Data Structure
;; ============================================================================

(defn fresh-terminal-data
  "Create a fresh terminal data map for a new player."
  []
  {:terminal-installed? false
   :installed-apps #{}})

;; ============================================================================
;; Access Functions
;; ============================================================================

(declare update-terminal-data-in-session!)

(defn get-terminal-data
  "Get terminal data for a player by UUID string."
  [uuid-str]
  (let [state (store/get-player-state* (runtime-hooks/require-player-state-session-id "terminal.player-data")
                                       uuid-str)]
    (or (:terminal-data state)
        (fresh-terminal-data))))

(defn get-terminal-data-in-session
  [session-id uuid-str]
  (let [state (store/get-player-state* session-id uuid-str)]
    (or (:terminal-data state)
        (fresh-terminal-data))))

(defn update-terminal-data!
  "Update terminal data for a player and mark as dirty."
  [uuid-str f & args]
  (apply update-terminal-data-in-session!
         (runtime-hooks/require-player-state-session-id "terminal.player-data")
         uuid-str
         f
         args))

(defn update-terminal-data-in-session!
  [session-id uuid-str f & args]
  (store/update-player-state!* session-id
                               uuid-str
                               #(apply update % :terminal-data f args))
  (store/mark-player-dirty! session-id uuid-str))

;; ============================================================================
;; Terminal Installation
;; ============================================================================

(defn terminal-installed?
  "Check if terminal is installed for a player."
  [uuid-str]
  (boolean (:terminal-installed? (get-terminal-data uuid-str))))

(defn terminal-installed-in-session?
  [session-id uuid-str]
  (boolean (:terminal-installed? (get-terminal-data-in-session session-id uuid-str))))

(defn install-terminal!
  "Mark terminal as installed for a player."
  [uuid-str]
  (log/info "Installing terminal for player:" uuid-str)
  (update-terminal-data! uuid-str assoc :terminal-installed? true))

(defn install-terminal-in-session!
  [session-id uuid-str]
  (log/info "Installing terminal for player:" uuid-str)
  (update-terminal-data-in-session! session-id uuid-str assoc :terminal-installed? true))

(defn uninstall-terminal!
  "Uninstall terminal and all apps for a player."
  [uuid-str]
  (log/info "Uninstalling terminal for player:" uuid-str)
  (update-terminal-data! uuid-str
                         (fn [_] (fresh-terminal-data))))

(defn uninstall-terminal-in-session!
  [session-id uuid-str]
  (log/info "Uninstalling terminal for player:" uuid-str)
  (update-terminal-data-in-session! session-id uuid-str
                                    (fn [_] (fresh-terminal-data))))

;; ============================================================================
;; App Management
;; ============================================================================

(defn get-installed-apps
  "Get set of installed app IDs for a player."
  [uuid-str]
  (or (:installed-apps (get-terminal-data uuid-str))
      #{}))

(defn get-installed-apps-in-session
  [session-id uuid-str]
  (or (:installed-apps (get-terminal-data-in-session session-id uuid-str))
      #{}))

(defn app-installed?
  "Check if a specific app is installed for a player."
  [uuid-str app-id]
  (contains? (get-installed-apps uuid-str) app-id))

(defn app-installed-in-session?
  [session-id uuid-str app-id]
  (contains? (get-installed-apps-in-session session-id uuid-str) app-id))

(defn install-app!
  "Install an app for a player."
  [uuid-str app-id]
  (log/info "Installing app" app-id "for player:" uuid-str)
  (update-terminal-data! uuid-str
                         update :installed-apps
                         (fnil conj #{}) app-id))

(defn install-app-in-session!
  [session-id uuid-str app-id]
  (log/info "Installing app" app-id "for player:" uuid-str)
  (update-terminal-data-in-session! session-id uuid-str
                                    update :installed-apps
                                    (fnil conj #{}) app-id))

(defn uninstall-app!
  "Uninstall an app for a player."
  [uuid-str app-id]
  (log/info "Uninstalling app" app-id "for player:" uuid-str)
  (update-terminal-data! uuid-str
                         update :installed-apps
                         disj app-id))

(defn uninstall-app-in-session!
  [session-id uuid-str app-id]
  (log/info "Uninstalling app" app-id "for player:" uuid-str)
  (update-terminal-data-in-session! session-id uuid-str
                                    update :installed-apps
                                    disj app-id))

(defn install-multiple-apps!
  "Install multiple apps at once for a player."
  [uuid-str app-ids]
  (log/info "Installing apps" app-ids "for player:" uuid-str)
  (update-terminal-data! uuid-str
                         update :installed-apps
                         (fnil into #{}) app-ids))

(defn install-multiple-apps-in-session!
  [session-id uuid-str app-ids]
  (log/info "Installing apps" app-ids "for player:" uuid-str)
  (update-terminal-data-in-session! session-id uuid-str
                                    update :installed-apps
                                    (fnil into #{}) app-ids))

;; ============================================================================
;; Serialization Helpers
;; ============================================================================

(defn terminal-data->nbt
  "Convert terminal data to NBT-serializable map."
  [terminal-data]
  {:terminal-installed (boolean (:terminal-installed? terminal-data))
   :installed-apps (vec (:installed-apps terminal-data))})

(defn nbt->terminal-data
  "Convert NBT map to terminal data."
  [nbt-map]
  {:terminal-installed? (boolean (:terminal-installed nbt-map))
   :installed-apps (set (map keyword (:installed-apps nbt-map)))})

;; ============================================================================
;; Initialization
;; ============================================================================

(defn ensure-terminal-data!
  "Ensure terminal data exists in player state. Called on player join."
  [uuid-str]
  (let [session-id (runtime-hooks/require-player-state-session-id "terminal.player-data")]
    (when-not (:terminal-data (store/get-player-state* session-id uuid-str))
      (store/update-player-state!* session-id
                                 uuid-str
                                 assoc :terminal-data (fresh-terminal-data)))))

(defn ensure-terminal-data-in-session!
  [session-id uuid-str]
  (when-not (:terminal-data (store/get-player-state* session-id uuid-str))
    (store/update-player-state!* session-id
                                 uuid-str
                                 assoc :terminal-data (fresh-terminal-data))))



