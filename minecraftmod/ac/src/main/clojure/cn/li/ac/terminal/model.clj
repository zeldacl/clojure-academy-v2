(ns cn.li.ac.terminal.model
  "Pure terminal player-state model and transitions.")

(def state-key
  :terminal-data)

(defn fresh-state
  []
  {:terminal-installed? false
   :installed-apps #{}})

(defn normalize-state
  [terminal-data]
  (let [data (or terminal-data {})]
    {:terminal-installed? (boolean (:terminal-installed? data))
     :installed-apps (into #{} (map keyword (or (:installed-apps data) #{})))}))

(defn terminal-installed?
  [terminal-data]
  (boolean (:terminal-installed? (normalize-state terminal-data))))

(defn app-installed?
  [terminal-data app-id]
  (contains? (:installed-apps (normalize-state terminal-data)) app-id))

(defn install-terminal
  [terminal-data]
  (assoc (normalize-state terminal-data) :terminal-installed? true))

(defn uninstall-terminal
  [_terminal-data]
  (fresh-state))

(defn install-app
  [terminal-data app-id]
  (update (normalize-state terminal-data)
          :installed-apps
          (fnil conj #{}) app-id))

(defn uninstall-app
  [terminal-data app-id]
  (update (normalize-state terminal-data)
          :installed-apps
          disj app-id))

(defn install-apps
  [terminal-data app-ids]
  (update (normalize-state terminal-data)
          :installed-apps
          (fnil into #{}) app-ids))
