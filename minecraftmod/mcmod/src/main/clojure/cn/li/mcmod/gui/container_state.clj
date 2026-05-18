(ns cn.li.mcmod.gui.container-state
  "Platform-neutral container lifecycle state for GUI infrastructure.

  This namespace owns menu/container lookup state used by shared Minecraft GUI
  code. AC installs business callbacks separately; platform adapters should not
  round-trip this generic state through AC."
  (:require [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(defonce active-containers
  (atom #{}))

(defonce player-containers
  (atom {}))

(defonce menu-containers
  (atom {}))

(defonce containers-by-id
  (atom {}))

(defn register-active-container!
  "Register a Clojure container as active."
  [container]
  (swap! active-containers conj container)
  (log/debug "Registered active GUI container; total=" (count @active-containers))
  nil)

(defn unregister-active-container!
  "Unregister a Clojure container when its Minecraft menu is closed."
  [container]
  (swap! active-containers disj container)
  (log/debug "Unregistered active GUI container; remaining=" (count @active-containers))
  nil)

(defn list-active-containers
  "Return the currently active Clojure containers."
  []
  @active-containers)

(defn- player-key
  [player]
  (when player
    (some-> (entity/player-get-uuid player) str)))

(defn register-player-container!
  "Register a container for a player UUID."
  [player container]
  (when-let [k (player-key player)]
    (swap! player-containers assoc k container)
    (log/debug "Registered GUI container for player" k))
  nil)

(defn unregister-player-container!
  "Remove a player's active GUI container mapping."
  [player]
  (when-let [k (player-key player)]
    (swap! player-containers dissoc k)
    (log/debug "Unregistered GUI container for player" k))
  nil)

(defn get-player-container
  "Get the active GUI container for a player."
  [player]
  (when-let [k (player-key player)]
    (get @player-containers k)))

(defn get-player-container-from-active
  "Find an active tabbed container for this player by scanning active containers."
  [player]
  (when-let [pk (player-key player)]
    (first
      (filter (fn [container]
                (and (contains? container :tab-index)
                     (when-let [p (:player container)]
                       (= (player-key p) pk))))
              @active-containers))))

(defn register-menu-container!
  "Register the Clojure container backing a Minecraft menu instance."
  [menu container]
  (when menu
    (swap! menu-containers assoc menu container)
    (log/debug "Registered GUI container for menu" (type menu)))
  nil)

(defn unregister-menu-container!
  "Remove menu -> Clojure container mapping."
  [menu]
  (when menu
    (swap! menu-containers dissoc menu)
    (log/debug "Unregistered GUI container for menu" (type menu)))
  nil)

(defn get-container-for-menu
  "Get the Clojure container backing a Minecraft menu instance."
  [menu]
  (get @menu-containers menu))

(defn register-container-by-id!
  "Register a Clojure container by Minecraft containerId/window id."
  [container-id container]
  (when (some? container-id)
    (swap! containers-by-id assoc (int container-id) container)
    (log/debug "Registered GUI container by id" container-id))
  nil)

(defn unregister-container-by-id!
  "Remove containerId/window id mapping."
  [container-id]
  (when (some? container-id)
    (swap! containers-by-id dissoc (int container-id))
    (log/debug "Unregistered GUI container by id" container-id))
  nil)

(defn get-container-by-id
  "Get a Clojure container by Minecraft containerId/window id."
  [container-id]
  (when (some? container-id)
    (get @containers-by-id (int container-id))))

(defn get-menu-container-id
  "Get a Minecraft menu/container window id via platform protocol or reflection."
  [menu]
  (when menu
    (or (try
          (entity/menu-get-container-id menu)
          (catch Exception _ nil))
        (try
          (let [f (.getDeclaredField (class menu) "containerId")]
            (.setAccessible f true)
            (.get f menu))
          (catch Exception _ nil)))))

(defn resolve-container-for-menu
  "Resolve a Clojure container for a menu, falling back to containerId lookup."
  [menu]
  (or (get-container-for-menu menu)
      (when-let [container-id (get-menu-container-id menu)]
        (get-container-by-id container-id))))

(defn clear-all!
  "Clear all GUI runtime state. Intended for tests/reloads."
  []
  (reset! active-containers #{})
  (reset! player-containers {})
  (reset! menu-containers {})
  (reset! containers-by-id {})
  nil)
