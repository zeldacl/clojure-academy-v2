(ns cn.li.ac.terminal.apps.media-backend
  "Lightweight client-side media playback backend for terminal media player.

  The original Scala backend used low-level streamed playback APIs. During
  migration we keep a pure AC-side state model and play registered sound events
  through the existing client sound effect queue bridge."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.util.log :as log]))

(def ^:private tracks
  [{:id :sisters-noise :title "Sisters' Noise" :sound-id "my_mod:em.arc_strong"}
   {:id :only-my-railgun :title "Only My Railgun" :sound-id "my_mod:em.railgun"}
   {:id :level5-judgelight :title "Level5 Judgelight" :sound-id "my_mod:tp.tp"}])

(defonce ^:private playback-state
  (atom {:track-idx 0
         :playing? false
         :paused? false
         :volume 1.0
         :last-track-id nil}))

(defn tracks-catalog
  []
  tracks)

(defn playback-state-snapshot
  []
  (let [{:keys [track-idx] :as st} @playback-state
        safe-idx (mod (max 0 (int track-idx)) (count tracks))]
    (assoc st :track (nth tracks safe-idx))))

(defn- clamp-volume
  [v]
  (float (max 0.0 (min 1.0 (double v)))))

(defn set-volume!
  [v]
  (let [nv (clamp-volume v)]
    (swap! playback-state assoc :volume nv)
    nv))

(defn select-track!
  [idx]
  (let [safe-idx (mod (max 0 (int idx)) (count tracks))]
    (swap! playback-state assoc :track-idx safe-idx :paused? false)
    (nth tracks safe-idx)))

(defn next-track!
  []
  (let [{:keys [track-idx]} @playback-state]
    (select-track! (inc track-idx))))

(defn prev-track!
  []
  (let [{:keys [track-idx]} @playback-state]
    (select-track! (dec track-idx))))

(defn stop!
  []
  (swap! playback-state assoc :playing? false :paused? false)
  true)

(defn play-current!
  "Queue current track as a client sound event and mark backend state as playing."
  []
  (let [{:keys [track volume]} (playback-state-snapshot)]
    (client-sounds/queue-sound-effect!
      {:type :sound
       :sound-id (:sound-id track)
       :volume volume
       :pitch 1.0})
    (swap! playback-state assoc
           :playing? true
           :paused? false
           :last-track-id (:id track))
    (log/info "Media backend queued track" (:id track) "volume" volume)
    track))

(defn toggle-pause!
  []
  (let [{:keys [playing? paused?]} @playback-state]
    (cond
      paused?
      (do
        (play-current!)
        :playing)

      playing?
      (do
        (swap! playback-state assoc :playing? false :paused? true)
        :paused)

      :else
      (do
        (play-current!)
        :playing))))

(defn status-lines
  []
  (let [{:keys [track playing? paused? volume]} (playback-state-snapshot)
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