(ns cn.li.ac.terminal.apps.media-backend
  "Lightweight client-side media playback backend for terminal media player.

  The original Scala backend used low-level streamed playback APIs. During
  migration we keep a pure AC-side state model and play registered sound events
  through the existing client sound effect queue bridge."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

(def ^:private tracks
  [{:id :sisters-noise :title "Sisters' Noise" :sound-id "my_mod:em.arc_strong"}
   {:id :only-my-railgun :title "Only My Railgun" :sound-id "my_mod:em.railgun"}
   {:id :level5-judgelight :title "Level5 Judgelight" :sound-id "my_mod:tp.tp"}])

(def ^:private default-playback-state
  {:track-idx 0
   :playing? false
   :paused? false
   :volume 1.0
   :last-track-id nil})

(defn create-playback-runtime
  []
  {::runtime ::playback-runtime
   :playback-state* (atom {})})

(defonce ^:private installed-playback-runtime
  (create-playback-runtime))

(def ^:dynamic *playback-runtime*
  installed-playback-runtime)

(def ^:dynamic *media-owner* nil)

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Media backend owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn- client-session-id
  [owner]
  (require-owner-value owner ":client-session-id"
                       (or (:client-session-id owner)
                           (:session-id owner)
                           runtime-hooks/*client-session-id*)))

(defn- profile-id
  [owner]
  (require-owner-value owner ":profile-id"
                       (or (:profile-id owner)
                           (:player-uuid owner)
                           (some-> (:player owner) str))))

(defn media-owner-key
  [owner]
  [(client-session-id owner)
   (or (:screen-id owner) :media-player)
   (profile-id owner)])

(defn- playback-runtime?
  [runtime]
  (and (map? runtime)
       (= ::playback-runtime (::runtime runtime))
       (some? (:playback-state* runtime))))

(defn call-with-playback-runtime
  [runtime f]
  (when-not (playback-runtime? runtime)
    (throw (ex-info "Expected playback runtime"
                    {:runtime runtime})))
  (binding [*playback-runtime* runtime]
    (f)))

(defmacro with-playback-runtime
  [runtime & body]
  `(call-with-playback-runtime ~runtime (fn [] ~@body)))

(defn- current-playback-runtime
  []
  *playback-runtime*)

(defn- playback-state-atom
  []
  (:playback-state* (current-playback-runtime)))

(defn- playback-states-snapshot
  []
  @(playback-state-atom))

(declare swap-playback-state!)

(defn- reduce-playback-state-event
  [state event payload]
  (case event
    :playback/set-volume
    (assoc state :volume (:volume payload))

    :playback/select-track
    (assoc state :track-idx (:track-idx payload)
                 :paused? false)

    :playback/stop
    (assoc state :playing? false :paused? false)

    :playback/started
    (assoc state
           :playing? true
           :paused? false
           :last-track-id (:track-id payload))

    :playback/paused
    (assoc state :playing? false :paused? true)

    state))

(defn- dispatch-playback-state-event!
  [owner event payload]
  (swap-playback-state! owner reduce-playback-state-event event payload))

(defn- swap-playback-state!
  [owner f & args]
  (let [owner-key (media-owner-key owner)]
    (swap! (playback-state-atom)
           (fn [states]
             (assoc states owner-key
                    (apply f (get states owner-key default-playback-state) args))))))

(defn reset-playback-states-for-test!
  []
  (reset! (playback-state-atom) {})
  nil)

(defn tracks-catalog
  []
  tracks)

(defn playback-state-snapshot
  [owner]
  (let [{:keys [track-idx] :as st} (get (playback-states-snapshot) (media-owner-key owner) default-playback-state)
        safe-idx (mod (max 0 (int track-idx)) (count tracks))]
    (assoc st :track (nth tracks safe-idx))))

(defn- clamp-volume
  [v]
  (float (max 0.0 (min 1.0 (double v)))))

(defn set-volume!
  [owner v]
  (let [nv (clamp-volume v)]
    (dispatch-playback-state-event! owner :playback/set-volume {:volume nv})
    nv))

(defn select-track!
  [owner idx]
  (let [safe-idx (mod (max 0 (int idx)) (count tracks))]
    (dispatch-playback-state-event! owner :playback/select-track {:track-idx safe-idx})
    (nth tracks safe-idx)))

(defn next-track!
  [owner]
  (let [{:keys [track-idx]} (playback-state-snapshot owner)]
    (select-track! owner (inc track-idx))))

(defn prev-track!
  [owner]
  (let [{:keys [track-idx]} (playback-state-snapshot owner)]
    (select-track! owner (dec track-idx))))

(defn stop!
  [owner]
  (dispatch-playback-state-event! owner :playback/stop nil)
  true)

(defn play-current!
  "Queue current track as a client sound event and mark backend state as playing."
  [owner]
  (let [{:keys [track volume]} (playback-state-snapshot owner)]
    (client-sounds/queue-sound-effect! owner
      {:type :sound
       :sound-id (:sound-id track)
       :volume volume
       :pitch 1.0})
    (dispatch-playback-state-event! owner :playback/started {:track-id (:id track)})
    (log/info "Media backend queued track" (:id track) "volume" volume)
    track))

(defn toggle-pause!
  [owner]
  (let [{:keys [playing? paused?]} (playback-state-snapshot owner)]
    (cond
      paused?
      (do
        (play-current! owner)
        :playing)

      playing?
      (do
        (dispatch-playback-state-event! owner :playback/paused nil)
        :paused)

      :else
      (do
        (play-current! owner)
        :playing))))

(defn status-lines
  [owner]
  (let [{:keys [track playing? paused? volume]} (playback-state-snapshot owner)
        state-text (cond
                     paused? "Paused"
                     playing? "Playing"
                     :else "Stopped")]
    [(str "Now selected: " (:title track))
     (str "State: " state-text)
     (str "Volume: " (format "%.2f" (double volume)))
     "Tracks:"
     "- Sisters' Noise"
     "- Only My Railgun"
     "- Level5 Judgelight"
     ""
     "Playback backend: active (sound queue bridge)"
    "UI controls are being migrated; preview on app open."]))