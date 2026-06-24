(ns cn.li.ac.terminal.catalog
  "Immutable terminal app catalog and pure queries (server-safe metadata only).")

(def apps
  [{:id :skill-tree
    :name "Skill Tree"
    :icon "my_mod:textures/guis/apps/skill_tree/icon.png"
    :description "View and manage your abilities"
    :category :abilities}
   {:id :settings
    :name "Settings"
    :icon "my_mod:textures/guis/apps/settings/icon.png"
    :description "Configure game settings"
    :category :system
    :pre-installed? true}
   {:id :tutorial
    :name "MisakaCloud"
    :icon "my_mod:textures/guis/apps/tutorial/icon.png"
    :icons ["my_mod:textures/guis/apps/tutorial/icon_0.png"
            "my_mod:textures/guis/apps/tutorial/icon_1.png"
            "my_mod:textures/guis/apps/tutorial/icon_2.png"]
    :description "Learn how to use your abilities"
    :category :help
    :pre-installed? true}
   {:id :freq-transmitter
    :name "Frequency Transmitter"
    :icon "my_mod:textures/guis/apps/freq_transmitter/icon.png"
    :description "Manage wireless frequencies"
    :category :wireless}
   {:id :media-player
    :name "Media Player"
    :icon "my_mod:textures/guis/apps/media_player/icon.png"
    :description "Browse AcademyCraft media tracks"
    :category :media}
   {:id :about
    :name "About"
    :icon "my_mod:textures/guis/apps/about/icon.png"
    :description "Credits and information"
    :category :help
    :pre-installed? true}])

(def ^:private apps-by-id-index
  (into {} (map (juxt :id identity) apps)))

(defn- apps-by-id
  []
  apps-by-id-index)

(defn app-ids
  []
  (mapv :id apps))

(defn app-by-id
  [app-id]
  (get (apps-by-id) app-id))

(defn app-exists?
  [app-id]
  (boolean (app-by-id app-id)))

(defn app-icon [app]
  (if-let [icons (seq (:icons app))]
    (let [r (rand)
          idx (cond (< r 0.2) 0
                    (< r 0.3) 1
                    :else 2)]
      (nth icons idx))
    (:icon app)))

(defn app-count
  []
  (count apps))

(defn ordered-apps
  []
  (->> apps
       (sort-by (fn [app]
                  [(str (or (:category app) ""))
                   (str (or (:name app) ""))
                   (str (or (:id app) ""))]))
       vec))
