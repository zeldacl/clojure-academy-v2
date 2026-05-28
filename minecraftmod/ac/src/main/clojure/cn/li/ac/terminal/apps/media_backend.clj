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

(defonce ^:private playback-runtime-override* (atom nil))

(defonce ^:private media-owner-override* (atom nil))

(defn- current-media-owner
  []
  @media-owner-override*)

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
  (let [prev-override @playback-runtime-override*]
    (try
      (reset! playback-runtime-override* runtime)
      (f)
      (finally
        (reset! playback-runtime-override* prev-override)))))

(defmacro with-playback-runtime
  [runtime & body]
  `(call-with-playback-runtime ~runtime (fn [] ~@body)))

(defn- current-playback-runtime
  []
  (or @playback-runtime-override*
      @installed-playback-runtime))

(defn- playback-state-atom
  []
  (:playback-state* (current-playback-runtime)))

(defn- playback-states-snapshot
  []
  @(playback-state-atom))

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
  ([]  
   (playback-state-snapshot (current-media-owner)))
  ([owner]
   (let [{:keys [track-idx] :as st} (get (playback-states-snapshot) (media-owner-key owner) default-playback-state)
         safe-idx (mod (max 0 (int track-idx)) (count tracks))]
     (assoc st :track (nth tracks safe-idx)))))

(defn- clamp-volume
  [v]
  (float (max 0.0 (min 1.0 (double v)))))

(defn set-volume!
  ([v]
  (set-volume! (current-media-owner) v))
  ([owner v]
  (let [nv (clamp-volume v)]
    (swap-playback-state! owner assoc :volume nv)
    nv)))

(defn select-track!
  ([idx]
  (select-track! (current-media-owner) idx))
  ([owner idx]
  (let [safe-idx (mod (max 0 (int idx)) (count tracks))]
    (swap-playback-state! owner assoc :track-idx safe-idx :paused? false)
    (nth tracks safe-idx))))

(defn next-track!
  ([]  
  (next-track! (current-media-owner)))
  ([owner]
  (let [{:keys [track-idx]} (playback-state-snapshot owner)]
    (select-track! owner (inc track-idx)))))

(defn prev-track!
  ([]  
  (prev-track! (current-media-owner)))
  ([owner]
  (let [{:keys [track-idx]} (playback-state-snapshot owner)]
    (select-track! owner (dec track-idx)))))

(defn stop!
  ([]  
  (stop! (current-media-owner)))
  ([owner]
  (swap-playback-state! owner assoc :playing? false :paused? false)
  true))

(defn play-current!
  "Queue current track as a client sound event and mark backend state as playing."
  ([]
    (play-current! (current-media-owner)))
  ([owner]
  (let [{:keys [track volume]} (playback-state-snapshot owner)]
    (client-sounds/queue-sound-effect! owner
      {:type :sound
       :sound-id (:sound-id track)
       :volume volume
       :pitch 1.0})
    (swap-playback-state! owner assoc
           :playing? true
           :paused? false
           :last-track-id (:id track))
    (log/info "Media backend queued track" (:id track) "volume" volume)
    track)))

(defn toggle-pause!
  ([]  
  (toggle-pause! (current-media-owner)))
  ([owner]
  (let [{:keys [playing? paused?]} (playback-state-snapshot owner)]
    (cond
      paused?
      (do
        (play-current! owner)
        :playing)

      playing?
      (do
        (swap-playback-state! owner assoc :playing? false :paused? true)
        :paused)

      :else
      (do
        (play-current! owner)
        :playing)))))

(defn status-lines
  ([]  
  (status-lines (current-media-owner)))
  ([owner]
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
    "UI controls are being migrated; preview on app open."])))