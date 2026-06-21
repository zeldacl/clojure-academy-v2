(ns cn.li.ac.terminal.player
  "Server-side terminal player state — persisted directly to player NBT.

  Mirrors upstream AcademyCraft TerminalData: independent NBT key per domain,
  no runtime-store dependency.  Uses player.getPersistentData() — Minecraft
  handles save/load automatically."
  (:require [cn.li.ac.terminal.model :as model]
            [cn.li.mcmod.platform.player-persistent-data :as player-pd]
            [cn.li.mcmod.util.log :as log]))

(def ^:private nbt-key "academy_terminal")

;; --- NBT helpers ---

(defn- load-state
  [tag]
  (when (.contains tag nbt-key)
    (try (clojure.edn/read-string (.getString tag nbt-key))
         (catch Exception _ nil))))

(defn- save-state!
  [tag state]
  (.putString tag nbt-key (pr-str state)))

;; --- Public API ---

(defn state
  [player]
  (or (load-state (player-pd/get-persistent-data! player))
      (model/fresh-state)))

(defn update-state!
  [player f & args]
  (let [tag (player-pd/get-persistent-data! player)
        current (or (load-state tag) (model/fresh-state))
        new-state (apply f current args)]
    (save-state! tag new-state)
    new-state))

;; --- Queries ---

(defn terminal-installed?
  [player]
  (model/terminal-installed? (state player)))

(defn installed-apps
  [player]
  (:installed-apps (model/normalize-state (state player))))

(defn app-installed?
  [player app-id]
  (model/app-installed? (state player) app-id))

;; --- Mutations ---

(defn install-terminal!
  [player]
  (log/info "Installing terminal for player:" (str (.getUUID player)))
  (update-state! player model/install-terminal))

(defn uninstall-terminal!
  [player]
  (log/info "Uninstalling terminal for player:" (str (.getUUID player)))
  (update-state! player model/uninstall-terminal))

(defn install-app!
  [player app-id]
  (log/info "Installing app" app-id "for player:" (str (.getUUID player)))
  (update-state! player model/install-app app-id))

(defn uninstall-app!
  [player app-id]
  (log/info "Uninstalling app" app-id "for player:" (str (.getUUID player)))
  (update-state! player model/uninstall-app app-id))

(defn install-apps!
  [player app-ids]
  (log/info "Installing apps" app-ids "for player:" (str (.getUUID player)))
  (update-state! player model/install-apps app-ids))
