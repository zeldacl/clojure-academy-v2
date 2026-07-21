(ns cn.li.mcmod.platform.media-playback
  "Media playback operations via Framework function map.
   Impl stored at [:platform :media-playback]. Client-only; no-ops safely
   when unavailable (e.g. dedicated server, or before client init runs)."
  (:require [cn.li.mcmod.framework :as fw]))

(def media-playback-keys
  #{:play! :stop! :set-volume! :playing?})

(defn install-media-playback!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :media-playback] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :media-playback])))
(defn current  [] (get-in @(fw/fw-atom) [:platform :media-playback]))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn play!*
  "Decode and play an .ogg file from an absolute filesystem path. Fire-and-forget
  (decoding happens off-thread; playback starts once ready). No true seek/pause —
  stopping and playing again always restarts from the beginning."
  [source-path volume]
  (call :play! source-path volume))

(defn stop!* [] (call :stop!))
(defn set-volume!* [volume] (call :set-volume! volume))
(defn playing?* [] (boolean (call :playing?)))
