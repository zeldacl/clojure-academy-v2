(ns cn.li.ac.terminal.client.apps.media
  "CLIENT-ONLY: media player terminal app with client playback state."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.client.runtime :as runtime]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

(def ^:private tracks
  [{:id :sisters-noise :title "Sisters' Noise" :sound-id "my_mod:em.arc_strong"}
   {:id :only-my-railgun :title "Only My Railgun" :sound-id "my_mod:em.railgun"}
   {:id :level5-judgelight :title "Level5 Judgelight" :sound-id "my_mod:tp.tp"}])

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

(defn play-current!
  [owner]
  (let [{:keys [track volume]} (playback-snapshot owner)]
    (client-sounds/queue-sound-effect! owner
      {:type :sound
       :sound-id (:sound-id track)
       :volume volume
       :pitch 1.0})
    (dispatch-playback! owner :playback/started {:track-id (:id track)})
    (log/info "Media player queued track" (:id track))
    track))

(defn status-lines
  [owner]
  (let [{:keys [track playing? paused? volume]} (playback-snapshot owner)
        state-text (cond paused? "Paused" playing? "Playing" :else "Stopped")]
    [(str "Now selected: " (:title track))
     (str "State: " state-text)
     (str "Volume: " (format "%.2f" (double volume)))
     "Tracks:"
     "- Sisters' Noise"
     "- Only My Railgun"
     "- Level5 Judgelight"
     ""
     "Playback uses the client sound queue bridge."]))

(defn- create-gui
  [player]
  (let [owner (media-owner player)
        root (cgui-core/create-widget :size [450 360])
        bg (cgui-core/create-widget :pos [0 0] :size [450 360])
        _ (comp/add-component! bg (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
        title (cgui-core/create-widget :pos [0 20] :size [450 30])
        _ (comp/add-component! title (comp/text-box :text "Media Player" :font :ac-normal :font-size 28 :color 0xFFFFFFFF))
        content-lines (into ["AcademyCraft Media Library" ""]
                            (status-lines owner))
        widgets (map-indexed
                 (fn [idx line]
                   (let [w (cgui-core/create-widget :pos [30 (+ 70 (* idx 15))] :size [390 15])]
                     (comp/add-component! w (comp/text-box :text line :font :ac-normal :font-size 8 :color 0xFFFFFFFF))
                     w))
                 content-lines)]
    (cgui-core/add-widget! root bg)
    (cgui-core/add-widget! root title)
    (doseq [w widgets]
      (cgui-core/add-widget! root w))
    root))

(defn open!
  [player]
  (log/info "Opening media player for player:" player)
  (let [owner (media-owner player)]
    (runtime/ensure-owner! owner)
    (play-current! owner)
    (client-bridge/open-simple-gui! (create-gui player) "Media Player")))
