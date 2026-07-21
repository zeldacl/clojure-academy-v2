(ns cn.li.ac.media.network
  "Server-side network handler for the Media Player app — the only
  server-authoritative piece is which internal tracks a player has acquired.
  Playback/volume/external-track scanning are entirely client-local, matching
  upstream (media_player volume is a client Forge config, not synced)."
  (:require [cn.li.ac.media.acquire :as acquire]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]))

;; Ad-hoc app message id, matching the freq-transmitter app's convention
;; (integer ids outside the core terminal:* string protocol).
(def media-get-state-msg 1010)

(defn- media->wire
  [media]
  {:id (name (:id media))
   :name (:name media)
   :desc (:desc media)
   :external? (boolean (:external? media))})

(defn- handle-get-state
  [_payload player]
  (try
    {:success true
     :medias (mapv media->wire (acquire/installed-medias player))}
    (catch Throwable e
      (log/error "Error in media get-state handler:" (ex-message e))
      {:success false :error (ex-message e)})))

(defn register-handlers!
  []
  (net-server/register-handler media-get-state-msg handle-get-state)
  (log/info "Media player network handlers registered"))
