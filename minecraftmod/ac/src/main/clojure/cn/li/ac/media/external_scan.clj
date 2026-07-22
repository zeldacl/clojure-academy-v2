(ns cn.li.ac.media.external-scan
  "Client-only: scans the external-media folder and registers found tracks
  into cn.li.ac.media.catalog. Matches upstream's acmedia/source/*.ogg
  folder scanning; unlike upstream, custom cover art loading and persisted
  custom name/desc are out of scope for this pass — edited names/descriptions
  live in memory only for the current session."
  (:require [cn.li.ac.media.catalog :as catalog]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.util.log :as log]))

(defn rescan!
  "(Re)populate the external-track catalog from disk. Safe to call multiple
  times (e.g. an in-app \"rescan\" button); existing in-memory name/desc
  edits are lost on rescan, matching a fresh directory listing."
  []
  (try
    (catalog/reset-external-media!)
    (doseq [{:keys [id source length-secs]} (or (when-let [fw-atom (fw/fw-atom)]
                                                  (platform/call-adapter fw-atom :media-library :scan-external-tracks!))
                                                [])]
      (catalog/register-external-media!
        {:id (keyword id)
         :name id
         :desc "External track"
         :source source
         :length-secs length-secs}))
    (log/info "Scanned external media tracks:" (count (catalog/external-medias)))
    (catch Throwable e
      (log/warn e "External media scan failed"))))
