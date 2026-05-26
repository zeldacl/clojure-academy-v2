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

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "GUI container owner requires %s" label)
                    {:owner owner
                     :required label}))))

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

(defn- session-key
  [owner]
  (require-owner-value owner ":session-id"
                       (or (:server-session-id owner)
                           (:client-session-id owner)
                           (:session-id owner))))

(defn- owner-player-key
  [owner]
  (require-owner-value owner ":player-uuid"
                       (or (some-> (:player-uuid owner) str)
                           (some-> (:player owner) player-key))))

(defn- container-owner-key
  [owner container-id]
  [(session-key owner) (owner-player-key owner) (int container-id)])

(defn- legacy-container-entry
  [container-id]
  (throw (ex-info "GUI container id lookup requires explicit owner"
                  {:container-id container-id})))

(defn register-player-container!
  "Register a container for a player UUID."
  [player container]
  (when-let [k (player-key player)]
    (swap! player-containers update k (fnil conj []) container)
    (log/debug "Registered GUI container for player" k))
  nil)

(defn unregister-player-container!
  "Remove a player's active GUI container mapping."
  ([player]
   (when-let [k (player-key player)]
     (swap! player-containers dissoc k)
     (log/debug "Unregistered GUI containers for player" k))
   nil)
  ([player container]
   (when-let [k (player-key player)]
     (swap! player-containers
            (fn [by-player]
              (let [remaining (vec (remove #(identical? container %) (get by-player k)))]
                (if (seq remaining)
                  (assoc by-player k remaining)
                  (dissoc by-player k)))))
     (log/debug "Unregistered GUI container for player" k))
   nil))

(defn get-player-container
  "Get the active GUI container for a player."
  [player]
  (when-let [k (player-key player)]
    (peek (get @player-containers k))))

(defn get-player-containers
  "Get all active GUI containers for a player."
  [player]
  (when-let [k (player-key player)]
    (get @player-containers k [])))

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
  ([container-id _container]
  (legacy-container-entry container-id))
  ([owner container-id container]
   (when (some? container-id)
     (swap! containers-by-id assoc (container-owner-key owner container-id) container)
     (log/debug "Registered GUI container by owner/id" (container-owner-key owner container-id)))
   nil))

(defn unregister-container-by-id!
  "Remove containerId/window id mapping."
  ([container-id]
   (when (some? container-id)
     (legacy-container-entry container-id))
   nil)
  ([owner container-id]
   (when (some? container-id)
     (swap! containers-by-id dissoc (container-owner-key owner container-id))
     (log/debug "Unregistered GUI container by owner/id" (container-owner-key owner container-id)))
   nil))

(defn get-container-by-id
  "Get a Clojure container by Minecraft containerId/window id."
  ([container-id]
   (when (some? container-id)
     (legacy-container-entry container-id)))
  ([owner container-id]
   (when (some? container-id)
     (get @containers-by-id (container-owner-key owner container-id)))))

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
  (get-container-for-menu menu))

(defn clear-all!
  "Clear all GUI runtime state. Intended for tests/reloads."
  []
  (reset! active-containers #{})
  (reset! player-containers {})
  (reset! menu-containers {})
  (reset! containers-by-id {})
  nil)
