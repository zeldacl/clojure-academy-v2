(ns cn.li.ac.gui.status-poller
  "CLIENT-ONLY: Shared status poller for machine GUIs.

  Replaces sync-to-client! calls which can't read tile state on the render thread.
  Sends a server query and updates container atoms from the response."
  (:require [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]))

(defn create-poller
  "Return a (fn [container tile]) that queries the server for status.

  msg-fn:  (fn [] -> msg-id) to generate message ID
  apply-resp: (fn [container resp]) to update atoms from response"
  [msg-fn apply-resp]
  (fn [container tile]
    (when-let [payload (try (net-helpers/tile-pos-payload tile) (catch Exception _ nil))]
      (net-client/send-to-server
        (msg-fn)
        payload
        (fn [resp]
          (when resp
            (apply-resp container resp)))))))
