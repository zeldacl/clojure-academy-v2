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
    (if-let [tracks (when-let [fw-atom (fw/fw-atom)]
                      (platform/call-adapter-optional
                        fw-atom :media-library :scan-external-tracks!))]
      (do
        (catalog/reset-external-media!)
        (doseq [{:keys [id source length-secs]} tracks]
          (catalog/register-external-media!
            {:id (keyword id)
             :name id
             :desc "External track"
             :source source
             :length-secs length-secs}))
        (log/info "Scanned external media tracks:" (count (catalog/external-medias))))
      ;; No :media-library adapter yet. Content init runs in the mod
      ;; constructor (Phase 1), while the loader installs this adapter from
      ;; FMLClientSetupEvent, so the startup call always lands here — it used
      ;; to swallow that as "0 tracks" and wipe the catalog, which is why no
      ;; external file was ever found. The media app rescans on open, where
      ;; the adapter does exist; leave whatever is already registered alone.
      (log/info "External media scan skipped: media-library adapter not installed yet"))
    (catch Throwable e
      (log/warn e "External media scan failed"))))
