(ns cn.li.ac.terminal.client.apps.media-reactive
  "Media catalogue and playback controller on Presentation Runtime.

   AC owns catalogue/playback semantics; Presentation owns retained rendering,
   focus and input routing. The controller exposes a pure HUD projection so
   the same state can be rendered by the combat HUD artifact."
  (:require [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.ac.media.catalog :as catalog]
            [cn.li.ac.media.external-scan :as external-scan]
            [cn.li.ac.media.network :as media-net]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private playback-session (atom {:current nil :last-track nil}))
(defonce ^:private media-state* (atom {:granted-internal [] :selected 0}))

(defn- media-playback-call [fn-key & args]
  (when-let [fw-atom (fw/fw-atom)]
    (try
      (apply platform/call-adapter fw-atom :media-playback fn-key args)
      (catch Throwable _ nil))))

(defn- playback-state []
  (or (media-playback-call :state)
      {:status :stopped :elapsed-secs 0.0 :volume 1.0}))

(defn- wire-track->local [{:keys [id name desc external?]}]
  (let [k (keyword id)
        base (or (catalog/media-by-id k) {:id k :source nil :length-secs 0.0})]
    (assoc base :id k :name (or name (:name base))
           :desc (or desc (:desc base)) :external? (boolean external?))))

(defn- all-tracks [state]
  (into (mapv wire-track->local (:granted-internal @state))
        (->> (catalog/external-medias) (sort-by (comp name :id)) vec)))

(defn- current-track [] (:current @playback-session))

(defn- display-time [secs]
  (let [total (max 0 (long (or secs 0.0)))]
    (format "%02d:%02d" (quot total 60) (rem total 60))))

(defn- display-length [secs]
  (display-time secs))

(defn- progress [track {:keys [elapsed-secs]}]
  (let [length (double (or (:length-secs track) 0.0))
        elapsed (double (or elapsed-secs 0.0))]
    (if (pos? length) (max 0.0 (min 1.0 (/ elapsed length))) 0.0)))

(defn- play-track! [track]
  (when-let [source (:source track)]
    (media-playback-call :play! source (double (or (:volume (playback-state)) 1.0)))
    (swap! playback-session assoc :current track :last-track track)
    track))

(defn- stop-playback! []
  (media-playback-call :stop!)
  (swap! playback-session assoc :current nil)
  nil)

(defn- toggle-playback! [track]
  (case (:status (playback-state))
    :playing (media-playback-call :pause!)
    :paused (media-playback-call :resume!)
    (or (play-track! track)
        (when-let [last (:last-track @playback-session)]
          (play-track! last)))))

(defn- edit-track-field! [track field value]
  (when (and (:external? track) (:id track))
    (let [value (str (or value ""))]
      (catalog/update-external-media! (:id track) {field value})
      (when-let [fw-atom (fw/fw-atom)]
        (platform/call-adapter fw-atom :media-library :save-track-meta!
                               (clojure.core/name (:id track)) field value)))))

(defn- seek-relative! [seconds]
  (let [state (playback-state)
        track (current-track)
        length (double (or (:length-secs track) 0.0))
        elapsed (double (or (:elapsed-secs state) 0.0))
        target (if (pos? length)
                 (max 0.0 (min length (+ elapsed (double seconds))))
                 (max 0.0 (+ elapsed (double seconds))))]
    (media-playback-call :seek! target)
    target))

(defn- volume-relative! [delta]
  (let [volume (double (or (:volume (playback-state)) 1.0))
        next-volume (max 0.0 (min 1.0 (+ volume (double delta))))]
    (media-playback-call :set-volume! next-volume)
    next-volume))

(defn- track-row [track]
  {:label (str (or (:name track) "Unnamed") "  "
               (display-length (:length-secs track))
               (when (seq (:desc track)) (str " - " (:desc track))))
   :action-label (if (= :playing (:status (playback-state))) "Play" "Play")
   :track-id (:id track)
   :external? (boolean (:external? track))})

(defn- state-snapshot [state selected status]
  (let [tracks (all-tracks state)
        index (max 0 (min (dec (max 1 (count tracks))) (int selected)))
        track (when (seq tracks) (nth tracks index))
        playback (playback-state)
        current (current-track)
        active (or current track)]
    {:title "Media Player"
     :items (mapv track-row tracks)
     :selected index
     :current-track (str "Now playing: " (or (:name current) ""))
     :progress (progress current playback)
     :elapsed (str (display-time (:elapsed-secs playback)) " / "
                   (display-length (:length-secs current)))
     :duration (display-length (:length-secs current))
     :volume (format "Volume: %d%%" (int (* 100.0 (double (or (:volume playback) 1.0)))))
     :volume-ratio (double (or (:volume playback) 1.0))
     :editing-external (boolean (:external? track))
     :edit-name (str (or (:name track) ""))
     :edit-desc (str (or (:desc track) ""))
     :button-left "Previous"
     :button-right "Next"
     :play-pause (case (:status playback) :playing "Pause" "Play")
     :stop "Stop"
     :seek-back "-10s"
     :seek-forward "+10s"
     :volume-down "Vol-"
     :volume-up "Vol+"
     :status (or status (if track "Select a track" "No media available"))}))

(defn refresh-active!
  "Refresh the shared playback projection. Called from the frame hook so
   elapsed/progress/HUD never freeze while the media screen is closed."
  []
  (let [playback (playback-state)
        current (current-track)]
    (when (and current (= :stopped (:status playback)))
      (swap! playback-session assoc :current nil))
    (swap! media-state* assoc :playback playback :current (current-track))
    @media-state*))

(defn hud-overlay
  "Pure retained-node projection for the always-on now-playing readout."
  [screen-w screen-h]
  (let [track (current-track)
        playback (playback-state)]
    (when (and track (not= :stopped (:status playback)))
      (let [x 8
            y (max 4 (- (int screen-h) 34))
            w (min 260 (max 120 (- (int screen-w) 16)))
            ratio (progress track playback)
            name (str (:name track))
            time (str (display-time (:elapsed-secs playback)) " / "
                      (display-length (:length-secs track)))]
        [{:kind :quad :x x :y y :w w :h 26 :rgba (unchecked-int 0xAA101018)}
         {:kind :text :text name :x (+ x 6) :y (+ y 4)
          :rgba (unchecked-int 0xFFFFFFFF)}
         {:kind :quad :x (+ x 6) :y (+ y 18) :w (- w 12) :h 3
          :rgba (unchecked-int 0xFF404040)}
         {:kind :quad :x (+ x 6) :y (+ y 18) :w (int (* (- w 12) ratio)) :h 3
          :rgba (unchecked-int 0xFF68B5FF)}
         {:kind :text :text time :x (- (+ x w) 60) :y (+ y 4)
          :rgba (unchecked-int 0xFFCCCCCC)}]))))

(defn- fetch-granted! [state refresh!]
  (if-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner media-net/media-get-state-msg {}
      (fn [response]
        (swap! state assoc :granted-internal
               (if (:success response) (:medias response) []))
        (reset! media-state* (merge @media-state* @state))
        (refresh!)))
    (log/warn "[AC-Media] no client owner; showing local media only")))

(defn open! []
  (external-scan/rescan!)
  (let [state (atom {:granted-internal [] :selected 0})
        _ (reset! media-state* @state)
        initial (state-snapshot state 0 "Select a track; buttons control playback")
        vm* (atom nil)
        refresh! (fn [selected status]
                   (let [snapshot (state-snapshot state selected status)]
                     (swap! media-state* merge @state)
                     (when-let [vm @vm*]
                       (presentation/present! vm snapshot))
                     snapshot))
        vm (application/mount!
             "application/media"
             "Media Player"
             initial
             (fn [action current]
               (let [tracks (all-tracks state)
                     idx (int (or (:selected-index current) (:selected current) 0))
                     selected-item (:selected-item current)
                     selected-track (or (some #(when (= (:track-id selected-item) (:id %)) %) tracks)
                                        (when (seq tracks) (nth tracks (max 0 (min (dec (count tracks)) idx)))))
                     next-idx (case action
                                :application/left (mod (dec idx) (max 1 (count tracks)))
                                :application/right (mod (inc idx) (max 1 (count tracks)))
                                :media/previous (mod (dec idx) (max 1 (count tracks)))
                                :media/next (mod (inc idx) (max 1 (count tracks)))
                                idx)
                     track (when (seq tracks) (nth tracks next-idx))
                     status (case action
                              :media/play (do (toggle-playback! selected-track) "Playback toggled")
                              :media/toggle (do (toggle-playback! selected-track) "Playback toggled")
                              :media/stop (do (stop-playback!) "Playback stopped")
                              :media/seek-back (do (seek-relative! -10.0) "Seeked -10s")
                              :media/seek-forward (do (seek-relative! 10.0) "Seeked +10s")
                              :media/volume-down (do (volume-relative! -0.1) "Volume decreased")
                              :media/volume-up (do (volume-relative! 0.1) "Volume increased")
                              :media/seek (do
                                            (let [fraction (max 0.0 (min 1.0 (double (or (:value current) 0.0))))
                                                  length (double (or (:length-secs selected-track) 0.0))]
                                              (media-playback-call :seek! (* fraction length)))
                                            "Seek position updated")
                              :media/volume-set (do
                                                   (volume-relative! (- (double (or (:value current) 0.0))
                                                                        (double (or (:volume (playback-state)) 1.0))))
                                                   "Volume updated")
                              :media/edit-name (do
                                                 (edit-track-field! selected-track :name (:value current))
                                                 "Name updated")
                              :media/edit-desc (do
                                                 (edit-track-field! selected-track :desc (:value current))
                                                 "Description updated")
                              (if track (str "Selected " (:name track)) "No media available"))]
                 (refresh! next-idx status)))
             #(stop-playback!))]
    (reset! vm* vm)
    (fetch-granted! state #(refresh! 0 "Media catalogue updated"))
    vm))