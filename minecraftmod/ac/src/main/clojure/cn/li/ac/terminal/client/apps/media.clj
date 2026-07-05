(ns cn.li.ac.terminal.client.apps.media
  "CLIENT-ONLY: media player terminal app ported from original AcademyCraft
  MediaApp / MediaGui.  Loads media_player.xml layout, builds track list from
  t_one template, wires play/pause/stop/volume controls.

  Sound stop uses the generic client-bridge hook registered by mc1201 sound effects."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.client.runtime :as runtime]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as xml-parser]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

;; --- Track definitions (matching original AcademyCraft media library) ---

(def ^:private tracks
  [{:id :sisters-noise :title "Sisters' Noise" :sound-id "my_mod:em.arc_strong"}
   {:id :only-my-railgun :title "Only My Railgun" :sound-id "my_mod:em.railgun"}
   {:id :level5-judgelight :title "Level5 Judgelight" :sound-id "my_mod:tp.tp"}])

;; --- Runtime state helpers ---

(defn- media-owner
  [player]
  (let [player-id (or (player-uuid/player-uuid player) (str player))]
    {:client-session-id (or runtime-hooks/*client-session-id*
                            [:media-client player-id])
     :screen-id :media-player
     :player-uuid player-id}))

(defn- default-playback
  []
  {:track-idx 0 :playing? false :paused? false :volume 1.0})

(defn- playback-snapshot
  [owner]
  (let [{:keys [track-idx] :as st}
        (merge (default-playback) (runtime/state-snapshot owner))
        safe-idx (mod (max 0 (int track-idx)) (count tracks))]
    (assoc st :track (nth tracks safe-idx))))

(defn reset-playback-for-test!
  []
  (runtime/reset-states-for-test!))

(defn- clamp-volume
  [v]
  (float (max 0.0 (min 1.0 (double v)))))

(defn- dispatch-playback!
  [owner event payload]
  (runtime/dispatch-event! owner event payload))

(defn- stop-media!
  "Stop all currently playing media sounds via the mcmod platform bridge."
  [owner]
  (try
    (client-bridge/stop-all-media! (:player-uuid owner))
    (catch Throwable _ nil)))

(defn- play-current!
  "Play the currently selected track. Stops any previous media first."
  [owner]
  (let [{:keys [track volume]} (playback-snapshot owner)]
    (stop-media! owner)
    (client-sounds/queue-sound-effect! owner
      {:type :sound
       :sound-id (:sound-id track)
       :volume volume
       :pitch 1.0})
    (dispatch-playback! owner :playback/started {:track-id (:id track)})
    (log/info "Media player playing track" (:id track))
    track))

;; --- UI construction ---

(defn- create-gui
  [player]
  (let [owner (media-owner player)
        doc  (xml-parser/read-xml (modid/asset-path "guis" "media_player.xml"))
        root (cgui-core/copy-widget (xml-parser/get-widget doc "back"))
        current-vol (atom 1.0)]

    ;; --- Track list (ElementList from t_one template) ---
    (let [area (cgui-core/find-widget root "area")
          list-w (cgui-core/create-widget :pos [0 0] :size [552 302])
          template (xml-parser/get-widget doc "t_one")]
      (when area
        (comp/add-component! list-w (comp/element-list :spacing 0))
        (doseq [[idx track] (map-indexed vector tracks)]
          (when-let [row (cgui-core/copy-widget template)]
            ;; Track title
            (when-let [title-w (cgui-core/find-widget row "title")]
              (comp/set-text! (comp/get-textbox-component title-w) (:title track)))
            ;; Click to play
            (events/on-left-click row
              (fn [_]
                (dispatch-playback! owner :playback/select-track {:track-idx idx})
                (play-current! owner)))
            (comp/list-add!
              (comp/get-widget-component list-w :element-list) row)))
        (cgui-core/add-widget! area list-w)))

    ;; --- Play/Pause button (pop) ---
    ;; Pause = stop + mark paused; resume = replay from beginning
    (when-let [pop-btn (cgui-core/find-widget root "pop")]
      (events/on-left-click pop-btn
        (fn [_]
          (let [{:keys [playing? paused?]} (playback-snapshot owner)]
            (if (or playing? paused?)
              (do
                (stop-media! owner)
                (dispatch-playback! owner :playback/paused {}))
              (play-current! owner))))))

    ;; --- Stop button ---
    (when-let [stop-btn (cgui-core/find-widget root "stop")]
      (events/on-left-click stop-btn
        (fn [_]
          (stop-media! owner)
          (dispatch-playback! owner :playback/stop {}))))

    ;; --- Volume bar (dragbar) ---
    (when-let [vol-bar (cgui-core/find-widget root "volume_bar")]
      (comp/add-component! vol-bar (comp/draggable))
      (events/on-drag vol-bar
        (fn [evt]
          (let [new-vol (clamp-volume (+ @current-vol (* (:dx evt) 0.003)))]
            (reset! current-vol new-vol)
            (dispatch-playback! owner :playback/set-volume {:volume new-vol})))))

    ;; --- Title text ---
    (when-let [title-w (cgui-core/find-widget root "title")]
      (comp/set-text! (comp/get-textbox-component title-w)
        (or (:title (first tracks)) "Media Player")))

    ;; --- Frame update: sync title with current track ---
    (events/on-frame root
      (fn [_]
        (let [{:keys [track]} (playback-snapshot owner)]
          (when-let [title-w (cgui-core/find-widget root "title")]
            (comp/set-text! (comp/get-textbox-component title-w) (:title track))))))

    root))

;; --- Public ---

(defn open!
  "Open the media player GUI. Auto-plays the first track."
  [player]
  (log/info "Opening media player for" (pr-str player))
  (let [owner (media-owner player)]
    (runtime/ensure-owner! owner)
    (play-current! owner)
    (client-bridge/open-simple-gui! (create-gui player) "Media Player")))
