(ns cn.li.mcmod.gui.container-state
  "Platform-neutral container lifecycle state for GUI infrastructure.

  This namespace owns menu/container lookup state used by shared Minecraft GUI
  code. AC installs business callbacks separately; platform adapters should not
  round-trip this generic state through AC."
  (:require [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(defonce active-containers
  (atom {}))

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

(defn- owner-base-key
  [owner]
  [(session-key owner) (owner-player-key owner)])

(defn- container-owner-key
  [owner container-id]
  [(session-key owner) (owner-player-key owner) (int container-id)])

(defn- owner-key-prefix?
  [base-key state-key]
  (= base-key (subvec (vec state-key) 0 2)))

(defn- session-key-prefix?
  [session-id state-key]
  (= session-id (first state-key)))

(defn- container-runtime-id
  [container]
  (or (:container-id container)
      (:window-id container)
      (:id container)
      (System/identityHashCode container)))

(defn- active-container-key
  [owner container]
  (conj (owner-base-key owner) (container-runtime-id container)))

(defn- container-owner
  [container]
  (or (:owner container)
      (select-keys container [:server-session-id :client-session-id :session-id :player-uuid :player])))

(defn- legacy-container-entry
  [container-id]
  (throw (ex-info "GUI container id lookup requires explicit owner"
                  {:container-id container-id})))

(defn register-active-container!
  "Register a Clojure container as active for an explicit owner."
  ([container]
   (throw (ex-info "Active GUI container registration requires explicit owner"
                   {:container container})))
  ([owner container]
   (swap! active-containers assoc (active-container-key owner container) container)
   (log/debug "Registered active GUI container; total=" (count @active-containers))
   nil))

(defn unregister-active-container!
  "Unregister a Clojure container when its Minecraft menu is closed."
  ([container]
   (throw (ex-info "Active GUI container unregister requires explicit owner"
                   {:container container})))
  ([owner container]
   (swap! active-containers dissoc (active-container-key owner container))
   (log/debug "Unregistered active GUI container; remaining=" (count @active-containers))
   nil))

(defn list-active-containers
  "Return active containers. Zero-arity returns a global diagnostic/tick snapshot;
   owner arity filters to one owner."
  ([]
   (vals @active-containers))
  ([owner]
   (let [base-key (owner-base-key owner)]
     (->> @active-containers
          (keep (fn [[state-key container]]
                  (when (owner-key-prefix? base-key state-key)
                    container)))))))

(defn register-player-container!
  "Register a container for an explicit owner."
  [owner container]
  (let [k (owner-base-key owner)]
    (swap! player-containers update k (fnil conj []) container)
    (log/debug "Registered GUI container for owner" k))
  nil)

(defn unregister-player-container!
  "Remove an owner's active GUI container mapping."
  ([owner]
   (let [k (owner-base-key owner)]
     (swap! player-containers dissoc k)
     (log/debug "Unregistered GUI containers for owner" k))
   nil)
  ([owner container]
   (let [k (owner-base-key owner)]
     (swap! player-containers
            (fn [by-player]
              (let [remaining (vec (remove #(identical? container %) (get by-player k)))]
                (if (seq remaining)
                  (assoc by-player k remaining)
                  (dissoc by-player k)))))
     (log/debug "Unregistered GUI container for owner" k))
   nil))

(defn get-player-container
  "Get the active GUI container for an explicit owner."
  [owner]
  (peek (get @player-containers (owner-base-key owner))))

(defn get-player-containers
  "Get all active GUI containers for an explicit owner."
  [owner]
  (get @player-containers (owner-base-key owner) []))

(defn get-player-container-from-active
  "Find an active tabbed container for an explicit owner by scanning active containers."
  [owner]
  (first
    (filter #(contains? % :tab-index)
            (list-active-containers owner))))

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
  (reset! active-containers {})
  (reset! player-containers {})
  (reset! menu-containers {})
  (reset! containers-by-id {})
  nil)

(defn clear-owner-containers!
  "Clear GUI runtime state for one owner."
  [owner]
  (let [base-key (owner-base-key owner)]
    (swap! active-containers
           (fn [containers]
             (into {}
                   (remove (fn [[state-key _container]]
                             (owner-key-prefix? base-key state-key)))
                   containers)))
    (swap! player-containers dissoc base-key)
    (swap! containers-by-id
           (fn [containers]
             (into {}
                   (remove (fn [[state-key _container]]
                             (owner-key-prefix? base-key state-key)))
                   containers)))
    (swap! menu-containers
           (fn [containers]
             (into {}
                   (remove (fn [[_menu container]]
                             (try
                               (= base-key (owner-base-key (container-owner container)))
                               (catch Exception _ false))))
                   containers))))
  nil)

(defn clear-session-containers!
  "Clear GUI runtime state for one client/server session id."
  [session-id]
  (swap! active-containers
         (fn [containers]
           (into {}
                 (remove (fn [[state-key _container]]
                           (session-key-prefix? session-id state-key)))
                 containers)))
  (swap! player-containers
         (fn [containers]
           (into {}
                 (remove (fn [[state-key _containers]]
                           (session-key-prefix? session-id state-key)))
                 containers)))
  (swap! containers-by-id
         (fn [containers]
           (into {}
                 (remove (fn [[state-key _container]]
                           (session-key-prefix? session-id state-key)))
                 containers)))
  (swap! menu-containers
         (fn [containers]
           (into {}
                 (remove (fn [[_menu container]]
                           (try
                             (= session-id (session-key (container-owner container)))
                             (catch Exception _ false))))
                 containers)))
  nil)

(defn container-state-snapshot
  "Return raw GUI runtime state for tests/diagnostics."
  []
  {:active-containers @active-containers
   :player-containers @player-containers
   :menu-containers @menu-containers
   :containers-by-id @containers-by-id})
