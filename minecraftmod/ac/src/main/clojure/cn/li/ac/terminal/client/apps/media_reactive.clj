(ns cn.li.ac.terminal.client.apps.media-reactive
  "Complete reactive replacement for media.clj — real track list + playback,
  matching upstream MediaGui/MediaBackend as closely as the simplified
  playback engine and list-templating framework allow:
  - play/stop only, no true pause/seek (framework :media-playback adapter)
  - external tracks' name/desc are NOT click-to-edit in this pass — the
    list-templating mechanism (cn.li.mcmod.ui.core/list-set!) rebuilds every
    row from one shared static XML template, so a row can't carry its own
    per-instance editable-field state the way freq_transmitter_reactive's
    hand-built (non-templated) rows do. Follow-up if this matters in practice."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.media.catalog :as catalog]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.xml :as ui-xml])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

;; Ad-hoc app message id — matches cn.li.ac.media.network/media-get-state-msg
;; (kept as a local literal rather than a cross-side require, matching the
;; freq-transmitter app's established convention).
(def ^:private media-get-state-msg 1010)

(def ^:private row-h 60.0)
(def ^:private visible-h 302.0)
(def ^:private thumb-min-y 169.0)
(def ^:private thumb-max-y 415.0)
(def ^:private thumb-travel (- thumb-max-y thumb-min-y))
(def ^:private vol-min-x 186.0)
(def ^:private vol-max-x 298.0)
(def ^:private vol-travel (- vol-max-x vol-min-x))
(def ^:private progress-full-w 554.0)

(def ^:private t-play (modid/asset-path "textures" "guis/apps/media_player/play.png"))
(def ^:private t-pause (modid/asset-path "textures" "guis/apps/media_player/pause.png"))

;; ============================================================================
;; Track list
;; ============================================================================

(defn- wire-track->local
  [{:keys [id name desc external?]}]
  {:id (keyword id) :name name :desc desc :external? (boolean external?)
   :source nil :length-secs 0.0})

(defn- all-tracks [state]
  ;; External tracks are inherently client-local (each player's own machine),
  ;; scanned directly from disk; only internal-track *acquisition* is
  ;; server-authoritative (see cn.li.ac.media.acquire).
  (into (vec (catalog/external-medias))
        (map wire-track->local (:granted-internal @state))))

(defn- fetch-granted! [state rebuild!]
  (net-client/send-to-server media-get-state-msg {}
    (fn [response]
      (swap! state assoc :granted-internal (if (:success response) (:medias response) []))
      (rebuild!))))

;; ============================================================================
;; Playback
;; ============================================================================

(defn- media-playback-call [fn-key & args]
  (when-let [fw-atom (fw/fw-atom)]
    (apply platform/call-adapter fw-atom :media-playback fn-key args)))

(defn- write-progress-fill! [^INode fill-node progress]
  (let [w (* progress-full-w (max 0.0 (min 1.0 (double progress))))]
    (when-not (== w (.getW fill-node))
      (.setW fill-node w)
      (.setFlag fill-node node/FLAG-LAYOUT-DIRTY))))

(defn- update-now-playing-display! [^UiRt r state]
  (let [{:keys [current elapsed-ms progress-fill-node]} @state
        playing? (boolean current)
        length (double (or (:length-secs current) 0.0))
        elapsed-secs (if playing? (min length (/ (double (or elapsed-ms 0)) 1000.0)) 0.0)
        progress (if (pos? length) (/ elapsed-secs length) 0.0)]
    (ui/set-prop! r :title :text (if current (:name current) ""))
    (ui/set-prop! r :play_time :text (catalog/display-length (if playing? elapsed-secs 0.0)))
    (when progress-fill-node (write-progress-fill! progress-fill-node progress))
    (ui/set-prop! r :pop :src (if playing? t-pause t-play))))

(defn- stop! [^UiRt r state]
  (media-playback-call :stop!)
  (swap! state assoc :current nil :play-start-ms 0 :elapsed-ms 0)
  (update-now-playing-display! r state))

(defn- play! [^UiRt r state track]
  (if-let [source (:source track)]
    (do
      (media-playback-call :play! source (double (:volume @state 1.0)))
      (swap! state assoc :current track :play-start-ms (System/currentTimeMillis) :elapsed-ms 0)
      (update-now-playing-display! r state))
    ;; Internal track with no bundled audio in this build — nothing to play.
    (swap! state assoc :current nil)))

(defn- toggle-play-pause! [^UiRt r state rebuild!]
  (if (:current @state)
    (stop! r state)
    (if-let [t (:last-track @state)]
      (play! r state t)
      (let [tracks (all-tracks state)]
        (when (seq tracks) (play! r state (first tracks))))))
  (rebuild!))

;; ============================================================================
;; List rows
;; ============================================================================

(defn- wire-track-row! [^UiRt r state rebuild! item track]
  (let [^INode title (ui/item-node item :title)
        ^INode time-n (ui/item-node item :time)
        ^INode desc (ui/item-node item :desc)
        ^INode edit-name (ui/item-node item :btn_edit_name)
        ^INode edit-desc (ui/item-node item :btn_edit_desc)]
    (ui/set-node-prop! r title :text (:name track))
    (ui/set-node-prop! r desc :text (:desc track))
    (ui/set-node-prop! r time-n :text (catalog/display-length (:length-secs track)))
    ;; Click-to-edit is not wired in this pass (see namespace docstring) —
    ;; keep the icons hidden rather than presenting a non-functional affordance.
    (when edit-name (.setVisible edit-name false))
    (when edit-desc (.setVisible edit-desc false))
    (rt/register-event! r (.getIdx ^INode item) :left-click
      (fn [_ _ _]
        (swap! state assoc :last-track track)
        (play! r state track)
        (rebuild!)))))

(defn- rebuild-list! [^UiRt r state rebuild!]
  (let [tracks (all-tracks state)]
    (ui/list-set! r "media-list" tracks
      (fn [rt item track] (wire-track-row! rt state rebuild! item track)))))

;; ============================================================================
;; Scrollbar / volume drag
;; ============================================================================

(defn- attach-scrollbar! [^UiRt r state]
  (let [drag-start-y (atom thumb-min-y)]
    (events/on! r :scroll_bar :mouse-scroll
      (fn [_ _ evt]
        (let [max-scroll (max 0.0 (- (* (count (all-tracks state)) row-h) visible-h))
              current (:scroll-px @state 0.0)
              new-px (max 0.0 (min max-scroll (- current (* (:delta evt) 10.0))))]
          (swap! state assoc :scroll-px new-px)
          (ui/set-prop! r :media-list :scroll-offset new-px))))
    (events/on! r :scroll_bar :drag-start
      (fn [_ _ _] (reset! drag-start-y (.getY ^INode (rt/node-by-id r :scroll_bar)))))
    (events/on! r :scroll_bar :drag
      (fn [_ _ evt]
        (let [max-scroll (max 0.0 (- (* (count (all-tracks state)) row-h) visible-h))
              new-y (max thumb-min-y (min thumb-max-y (+ @drag-start-y (double (:dy evt)))))
              progress (/ (- new-y thumb-min-y) thumb-travel)
              new-px (* progress max-scroll)]
          (swap! state assoc :scroll-px new-px)
          (ui/set-prop! r :media-list :scroll-offset new-px))))))

(defn- attach-volume-drag! [^UiRt r state]
  (let [drag-start-x (atom vol-min-x)]
    (events/on! r :volume_bar :drag-start
      (fn [_ _ _] (reset! drag-start-x (.getX ^INode (rt/node-by-id r :volume_bar)))))
    (events/on! r :volume_bar :drag
      (fn [_ _ evt]
        (let [new-x (max vol-min-x (min vol-max-x (+ @drag-start-x (double (:dx evt)))))
              ^INode vb (rt/node-by-id r :volume_bar)
              progress (max 0.0 (min 1.0 (/ (- new-x vol-min-x) vol-travel)))]
          (.setX vb new-x)
          (.setFlag vb node/FLAG-LAYOUT-DIRTY)
          (swap! state assoc :volume progress)
          (media-playback-call :set-volume! progress))))))

;; ============================================================================
;; Entry points
;; ============================================================================

(defn create-runtime []
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/new/media_player.xml"))
        _ (rt/build! r spec)
        progress-fill (rt/build-child! r
                        {:kind :box :props {:id :media-progress-fill :x 0.0 :y 0.0
                                             :w 0.0 :h 6.0 :fill 0xFFFFFFFF}}
                        (rt/node-by-id r :progress))
        state (atom {:granted-internal [] :current nil :last-track nil
                      :play-start-ms 0 :elapsed-ms 0 :volume 1.0 :scroll-px 0.0
                      :progress-fill-node progress-fill})
        rebuild! (fn rebuild-fn [] (rebuild-list! r state rebuild-fn))]
    (rt/put-user-signal! r :media-progress-tick
      (sig/computed-o [(rt/clock-ms-sig r)]
        (fn [_]
          (when (:current @state)
            (swap! state assoc :elapsed-ms (- (System/currentTimeMillis) (:play-start-ms @state)))
            (update-now-playing-display! r state))
          nil)))
    (events/on! r :pop :left-click (fn [_ _ _] (toggle-play-pause! r state rebuild!)))
    (events/on! r :stop :left-click (fn [_ _ _] (stop! r state)))
    (attach-scrollbar! r state)
    (attach-volume-drag! r state)
    (fetch-granted! state rebuild!)
    (rebuild!)
    (update-now-playing-display! r state)
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "Media Player")))
