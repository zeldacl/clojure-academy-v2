(ns cn.li.mc1201.client.session-cleanup
  "Shared client owner cleanup/orchestration for disconnects and server switches."
  (:require [cn.li.mc1201.client.effects.level-renderer :as level-renderer]
            [cn.li.mc1201.client.overlay.renderer :as overlay-renderer]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]))

(defonce ^:private lifecycle-state
  (atom {:connection-key nil
         :owner nil}))

(defn cleanup-state-snapshot
  []
  @lifecycle-state)

(defn reset-cleanup-state-for-test!
  ([]
   (reset-cleanup-state-for-test! {:connection-key nil
                                   :owner nil}))
  ([snapshot]
   (reset! lifecycle-state {:connection-key (:connection-key snapshot)
                            :owner (:owner snapshot)})
   nil))

(defn- local-player
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (.player mc)))

(defn- close-current-screen!
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when (some? (.screen mc))
      (.setScreen mc nil)))
  nil)

(defn clear-owner-state!
  ([owner]
   (clear-owner-state! owner {}))
  ([owner {:keys [clear-owner-input-state!]
           :or {clear-owner-input-state! nil}}]
   (let [owner* (or owner {})
         session-id (:client-session-id owner*)]
     (close-current-screen!)
     (when (fn? clear-owner-input-state!)
       (clear-owner-input-state! owner*))
     (runtime-hooks/client-clear-owner-state! owner*)
     (overlay-state/clear-client-activated! owner*)
     (overlay-renderer/clear-overlay-render-state! owner*)
     (level-renderer/clear-owner-walk-speed! owner* ^LocalPlayer (local-player))
     (when session-id
       (net-client/clear-client-session-state! session-id))
     nil)))

(defn tick-connection-change!
  ([]
   (tick-connection-change! {}))
  ([opts]
   (let [current-connection-key (client-session/connection-key)
         current-owner (client-session/current-local-player-owner)
         {:keys [connection-key owner]} @lifecycle-state]
     (when (and owner
                (not= connection-key current-connection-key))
       (try
         (clear-owner-state! owner opts)
         (catch Exception e
           (log/error "Failed to clear client owner state during connection transition" e))))
     (reset! lifecycle-state {:connection-key current-connection-key
                              :owner current-owner})
     nil)))