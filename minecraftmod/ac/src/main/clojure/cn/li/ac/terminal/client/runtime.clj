(ns cn.li.ac.terminal.client.runtime
  "CLIENT-ONLY: shared owner-key state runtime for terminal UI."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def ^:private default-owner-state
  {:terminal-installed? false
   :installed-apps #{}
   :available-apps []
   :loading? false
   :page 0})

(def ^:private default-runtime-state
  {:next-generation 1
   :owners {}})

;; Terminal runtime — Framework [:service :terminal-runtime]

(def ^:private term-path [:service :terminal-runtime])

(defn- runtime-state-atom []
  (if-let [fw-atom fw/*framework*]
    (or (get-in @fw-atom term-path)
        (let [a (atom default-runtime-state)]
          (swap! fw-atom assoc-in term-path a)
          a))
    (atom default-runtime-state)))

(def ^:dynamic *owner* nil)

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Terminal owner requires %s" label)
                    {:owner owner :required label}))))

(defn- client-session-id
  [owner]
  (require-owner-value owner ":client-session-id"
                       (or (:client-session-id owner)
                           (:session-id owner)
                           runtime-hooks/*client-session-id*)))

(defn- owner-player-id
  [owner]
  (require-owner-value owner ":player-uuid" (:player-uuid owner)))

(defn owner-key
  [owner]
  [(client-session-id owner)
   (or (:screen-id owner) :terminal)
   (owner-player-id owner)])

(defn- runtime?
  [runtime]
  (and (map? runtime)
       (= ::terminal-client-runtime (::runtime runtime))
       (some? (:runtime-state* runtime))))

(defn call-with-runtime [runtime f] (f runtime))

(defmacro with-runtime [runtime & body]
  `(call-with-runtime ~runtime (fn [] ~@body)))

(defn- state-atom []
  (runtime-state-atom))

(defn- runtime-snapshot
  []
  @(state-atom))

(defn- public-state
  [entry]
  (if entry
    (dissoc entry :generation)
    default-owner-state))

(defn ensure-owner!
  [owner]
  (let [key (owner-key owner)
        generation* (volatile! nil)]
    (swap! (state-atom)
           (fn [{:keys [next-generation] :as rs}]
             (if-let [entry (get-in rs [:owners key])]
               (do (vreset! generation* (:generation entry)) rs)
               (let [generation next-generation]
                 (vreset! generation* generation)
                 (-> rs
                     (assoc :next-generation (inc next-generation))
                     (assoc-in [:owners key]
                               (assoc default-owner-state :generation generation)))))))
    @generation*))

(defn player-owner
  [player-uuid]
  {:client-session-id (or runtime-hooks/*client-session-id*
                          [:terminal-client player-uuid])
   :screen-id :terminal
   :player-uuid player-uuid})

(defn call-with-owner
  [owner f]
  (binding [*owner* owner]
    (f)))

(defmacro with-owner
  [owner & body]
  `(call-with-owner ~owner (fn [] ~@body)))

(defn state-snapshot
  [owner]
  (public-state (get-in (runtime-snapshot) [:owners (owner-key owner)])))

(declare swap-state!)

(defn- reduce-event
  [state event payload]
  (case event
    :terminal/query-response
    (merge state
           {:terminal-installed? (:terminal-installed? payload)
            :installed-apps (into #{} (map keyword (:installed-apps payload)))
            :available-apps (mapv keyword (:available-apps payload))})

    :terminal/install-start (assoc state :loading? true)

    :terminal/install-result
    (cond-> (assoc state :loading? false)
      (:success payload) (assoc :terminal-installed? true))

    :terminal/install-app-start (assoc state :loading? true)

    :terminal/install-app-result
    (cond-> (assoc state :loading? false)
      (:success payload) (update :installed-apps conj (:app-id payload)))

    :terminal/uninstall-app-result
    (cond-> state
      (:success payload) (update :installed-apps disj (:app-id payload)))

    :terminal/set-page
    (assoc state :page (int (or (:page payload) 0)))

    :playback/set-volume
    (assoc state :volume (:volume payload))

    :playback/select-track
    (assoc state :track-idx (:track-idx payload) :paused? false)

    :playback/stop
    (assoc state :playing? false :paused? false)

    :playback/started
    (assoc state :playing? true :paused? false :last-track-id (:track-id payload))

    :playback/paused
    (assoc state :playing? false :paused? true)

    state))

(defn dispatch-event!
  [owner event payload]
  (swap-state! owner reduce-event event payload))

(defn- swap-state!
  [owner f & args]
  (ensure-owner! owner)
  (let [key (owner-key owner)]
    (swap! (state-atom)
           (fn [rs]
             (apply update-in rs [:owners key] f args)))))

(defn owner-active?
  [owner generation]
  (= generation
     (get-in (runtime-snapshot) [:owners (owner-key owner) :generation])))

(defn clear-state!
  [owner]
  (swap! (state-atom) update :owners dissoc (owner-key owner))
  nil)

(defn reset-states-for-test!
  []
  (reset! (state-atom) default-runtime-state)
  nil)
