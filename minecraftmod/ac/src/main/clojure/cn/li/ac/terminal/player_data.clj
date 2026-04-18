(ns cn.li.ac.terminal.player-data
  "Terminal data extension for player state.

  Extends the existing player-state atom with terminal installation tracking
  and app management. Reuses the existing dirty tracking and sync mechanisms."
  (:require [cn.li.ac.ability.state.player :as ps]
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

(defn get-terminal-data
  "Get terminal data for a player by UUID string."
  [uuid-str]
  (let [state (ps/get-player-state uuid-str)]
    (or (:terminal-data state)
        (fresh-terminal-data))))

(defn update-terminal-data!
  "Update terminal data for a player and mark as dirty."
  [uuid-str f & args]
  (apply ps/update-player-state! uuid-str update :terminal-data f args)
  (ps/mark-dirty! uuid-str))

;; ============================================================================
;; Terminal Installation
;; ============================================================================

(defn terminal-installed?
  "Check if terminal is installed for a player."
  [uuid-str]
  (boolean (:terminal-installed? (get-terminal-data uuid-str))))

(defn install-terminal!
  "Mark terminal as installed for a player."
  [uuid-str]
  (log/info "Installing terminal for player:" uuid-str)
  (update-terminal-data! uuid-str assoc :terminal-installed? true))

(defn uninstall-terminal!
  "Uninstall terminal and all apps for a player."
  [uuid-str]
  (log/info "Uninstalling terminal for player:" uuid-str)
  (update-terminal-data! uuid-str
                         (fn [_] (fresh-terminal-data))))

;; ============================================================================
;; App Management
;; ============================================================================

(defn get-installed-apps
  "Get set of installed app IDs for a player."
  [uuid-str]
  (or (:installed-apps (get-terminal-data uuid-str))
      #{}))

(defn app-installed?
  "Check if a specific app is installed for a player."
  [uuid-str app-id]
  (contains? (get-installed-apps uuid-str) app-id))

(defn install-app!
  "Install an app for a player."
  [uuid-str app-id]
  (log/info "Installing app" app-id "for player:" uuid-str)
  (update-terminal-data! uuid-str
                         update :installed-apps
                         (fnil conj #{}) app-id))

(defn uninstall-app!
  "Uninstall an app for a player."
  [uuid-str app-id]
  (log/info "Uninstalling app" app-id "for player:" uuid-str)
  (update-terminal-data! uuid-str
                         update :installed-apps
                         disj app-id))

(defn install-multiple-apps!
  "Install multiple apps at once for a player."
  [uuid-str app-ids]
  (log/info "Installing apps" app-ids "for player:" uuid-str)
  (update-terminal-data! uuid-str
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
  (when-not (:terminal-data (ps/get-player-state uuid-str))
    (ps/update-player-state! uuid-str
                             assoc :terminal-data (fresh-terminal-data))))
