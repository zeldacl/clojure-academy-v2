(ns cn.li.mc1201.client.audio.media-playback
  "Bridges the shared ExternalOggPlayer (raw OpenAL playback of an external
  .ogg file path) into mcmod.platform.media-playback's function-map contract,
  and the shared OggMetadata scanner into mcmod.platform.media-library's.
  Shared by Forge and Fabric — both loaders install this identically."
  (:require [cn.li.mcmod.platform.media-library :as media-library-bridge]
            [cn.li.mcmod.platform.media-playback :as media-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.client.audio ExternalOggPlayer OggMetadata]
           [net.minecraft.client Minecraft]
           [java.io File]))

(defn play!
  [source-path volume]
  (when source-path
    (ExternalOggPlayer/play source-path (float volume))))

(defn stop!
  []
  (ExternalOggPlayer/stop))

(defn set-volume!
  [volume]
  (ExternalOggPlayer/setVolume (float volume)))

(defn playing?
  []
  (ExternalOggPlayer/isPlaying))

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
    (->> (OggMetadata/listOggFiles (source-folder))
         (keep file->track)
         vec)
    (catch Throwable e
      (log/warn e "Failed to scan external media tracks")
      [])))

(defn install-media-playback-bridge!
  []
  (media-bridge/install-media-playback!
    {:play! play!
     :stop! stop!
     :set-volume! set-volume!
     :playing? playing?}
    "mc1201-media-playback")
  (media-library-bridge/install-media-library!
    {:scan-external-tracks! scan-external-tracks!}
    "mc1201-media-library"))
