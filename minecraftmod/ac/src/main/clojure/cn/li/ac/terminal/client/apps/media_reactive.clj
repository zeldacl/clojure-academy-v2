(ns cn.li.ac.terminal.client.apps.media-reactive
  "Media catalogue and playback controller on Presentation Runtime."
  (:require [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.media.catalog :as catalog]
            [cn.li.ac.media.external-scan :as external-scan]
            [cn.li.ac.media.network :as media-net]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))
(defonce ^:private playback-session (atom {:current nil :last-track nil}))

(defn- wire-track->local [{:keys [id name desc external?]}]
  (let [k (keyword id)
        base (or (catalog/media-by-id k) {:id k :source nil :length-secs 0.0})]
    (assoc base :id k :name (or name (:name base))
           :desc (or desc (:desc base)) :external? (boolean external?))))

(defn- all-tracks [state]
  (into (mapv wire-track->local (:granted-internal @state))
        (->> (catalog/external-medias) (sort-by (comp name :id)) vec)))

(defn- media-playback-call [fn-key & args]
  (when-let [fw-atom (fw/fw-atom)]
    (apply platform/call-adapter fw-atom :media-playback fn-key args)))

(defn- playback-state []
  (or (media-playback-call :state)
      {:status :stopped :elapsed-secs 0.0 :volume 1.0}))

(defn- display-time [secs]
  (let [total (max 0 (long (or secs 0.0)))]
    (format "%02d:%02d" (quot total 60) (rem total 60))))

(defn- current-track [] (:current @playback-session))

(defn- play-track! [track]
  (when-let [source (:source track)]
    (media-playback-call :play! source 1.0)
    (swap! playback-session assoc :current track :last-track track)))

(defn- toggle-playback! [track]
  (let [{:keys [status]} (playback-state)]
    (case status
      :playing (media-playback-call :pause!)
      :paused (media-playback-call :resume!)
      (when track (play-track! track)))))

(defn- stop-playback! []
  (media-playback-call :stop!)
  (swap! playback-session assoc :current nil))

(defn- fetch-granted! [state refresh!]
  (if-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner media-net/media-get-state-msg {}
      (fn [response]
        (swap! state assoc :granted-internal
               (if (:success response) (:medias response) []))
        (refresh!)))
    (log/warn "[AC-Media] no client owner; showing local media only")))

(defn- lines [state]
  (let [tracks (all-tracks state)]
    (if (seq tracks)
      (mapv (fn [{:keys [name desc length-secs]}]
              {:label (str (or name "Unnamed")
                           "  " (display-time length-secs)
                           (when (seq desc) (str " - " desc)))}) tracks)
      [{:label "No media available"}])))

(defn open! []
  (external-scan/rescan!)
  (let [state (atom {:granted-internal [] :selected 0 :lines []})
        refresh! (fn []
                   (swap! state assoc :lines (lines state))
                   @state)
        _ (refresh!)
        vm (application/mount!
             "application/media"
             "Media Player"
             {:lines (:lines @state)
              :status "Enter play/pause; Escape closes"
              :button-left {:label "Previous" :visible? true}
              :button-right {:label "Next" :visible? true}}
             (fn [action current]
               (let [tracks (all-tracks state)
                     idx (int (or (:selected current) 0))
                     next-idx (case action
                                :application/left (mod (dec idx) (max 1 (count tracks)))
                                :application/right (mod (inc idx) (max 1 (count tracks)))
                                idx)
                     track (when (seq tracks) (nth tracks next-idx))]
                 (when (= action :application/activate)
                   (toggle-playback! track))
                 {:selected next-idx
                  :lines (lines state)
                  :status (if track (str "Selected " (:name track)) "No media available")}))
             #(stop-playback!))]
    (fetch-granted! state
                    (fn []
                      (when-let [refresh-state (:refresh! vm)]
                        (refresh-state {:lines (lines state)}))))
    vm))
