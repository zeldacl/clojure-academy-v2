(ns cn.li.mcmod.platform.media-library
  "External media-file discovery via Framework function map.
   Impl stored at [:platform :media-library]. Client-only; no-ops safely
   when unavailable."
  (:require [cn.li.mcmod.framework :as fw]))

(defn install-media-library!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :media-library] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :media-library])))
(defn current  [] (get-in @(fw/fw-atom) [:platform :media-library]))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn scan-external-tracks!
  "Scan the platform's external-media folder (upstream: <gameDir>/acmedia/source/*.ogg)
  and return a vector of {:id :source :length-secs}. No cover-art loading in
  this pass — external tracks use the generic missing-cover icon."
  []
  (or (call :scan-external-tracks!) []))
