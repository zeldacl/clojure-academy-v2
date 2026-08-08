(ns cn.li.mcbase.client.audio.media-playback
  "Bridges the shared ExternalOggPlayer (raw OpenAL playback of an external
  .ogg file path) and OggMetadata scanner into framework platform adapters.
  Shared by Forge and Fabric — both loaders install this identically."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcbase.client.audio ExternalOggPlayer]
           [cn.li.mcbase.client.audio OggMetadata]
           [net.minecraft.client Minecraft]
           [java.io File]))

(defn play!
  [source-path volume]
  (when source-path
    (ExternalOggPlayer/play source-path (float volume))))

(defn stop!
  []
  (ExternalOggPlayer/stop))

(defn pause!
  []
  (ExternalOggPlayer/pause))

(defn resume!
  []
  (ExternalOggPlayer/resume))

(defn set-volume!
  [volume]
  (ExternalOggPlayer/setVolume (float volume)))

(defn playing?
  []
  (ExternalOggPlayer/isPlaying))

(defn playback-state
  []
  {:status (keyword (ExternalOggPlayer/getPlaybackState))
   :elapsed-secs (double (ExternalOggPlayer/getElapsedSeconds))
   :volume (double (ExternalOggPlayer/getVolume))})

;; ============================================================================
;; External track discovery — <gameDir>/acmedia/source/*.ogg (same folder
;; layout as upstream) probed for duration only; no full decode, no cover art.
;; ============================================================================

(defn- source-folder
  ^File []
  (let [^Minecraft mc (Minecraft/getInstance)
        game-dir (.gameDirectory mc)]
    (File. game-dir "acmedia/source")))

(defn- file->track [^File f]
  (let [name (.getName f)
        id (subs name 0 (- (count name) 4)) ;; strip ".ogg"
        info (OggMetadata/probe (.getAbsolutePath f))]
    (when info
      {:id id
       :source (.getAbsolutePath f)
       :length-secs (double (.-lengthSecs info))})))

(defn scan-external-tracks!
  []
  (try
    ;; Upstream MediaManager.checkPath mkdirs the folder on load, so players
    ;; have somewhere to drop files. listOggFiles just returns empty for a
    ;; missing directory, which left no hint that the path even existed.
    (let [^File dir (source-folder)]
      (when-not (.isDirectory dir)
        (.mkdirs dir)))
    (->> (OggMetadata/listOggFiles (source-folder))
         (keep file->track)
         vec)
    (catch Throwable e
      (log/warn e "Failed to scan external media tracks")
      [])))

(defn install-media-playback-bridge!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (platform/install-adapter!
      fw-atom
      :media-playback
      {:play! play!
       :stop! stop!
       :pause! pause!
       :resume! resume!
       :set-volume! set-volume!
       :playing? playing?
       :state playback-state})
    (platform/install-adapter!
      fw-atom
      :media-library
      {:scan-external-tracks! scan-external-tracks!}))
  nil)
