(ns cn.li.mc1201.client.screen.host
  "CLIENT-ONLY generic screen host for legacy managed screens."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as client-ui]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]))

(defn- with-client-session
  [session-id f]
  (client-ui/with-client-ctx-fn {:session-id session-id} f))

(defn- augment-payload-owner
  [payload]
  (let [payload (or payload {})
        owner (client-session/require-local-player-owner)
        payload-player-uuid (:player-uuid payload)
        payload-session-id (:client-session-id payload)]
    (when (and payload-player-uuid
               (not= payload-player-uuid (:player-uuid owner)))
      (throw (ex-info "Managed screen payload player UUID must match local owner"
                      {:payload payload
                       :owner owner})))
    (when (and payload-session-id
               (not= payload-session-id (:client-session-id owner)))
      (throw (ex-info "Managed screen payload client session must match local owner"
                      {:payload payload
                       :owner owner})))
    (merge payload owner {:player-uuid (:player-uuid owner)})))

(defn- create-host-screen
  ([title click-fn hover-fn close-fn]
   (create-host-screen title click-fn hover-fn close-fn nil))
  ([title click-fn hover-fn close-fn char-typed-fn]
   (DelegatingScreen.
     (Component/literal title)
     (fn [_this ^GuiGraphics _graphics mouse-x mouse-y _partial-tick]
       (when hover-fn (hover-fn mouse-x mouse-y)))
     (fn [_this key scancode modifiers]
       (cond
         (= key 256)
         (let [^Minecraft mc (Minecraft/getInstance)]
           (.setScreen mc nil)
           true)
         (and char-typed-fn (= key 259))
         (do (char-typed-fn \backspace) true)
         (and char-typed-fn (= key 257))
         (do (char-typed-fn \newline) true)
         :else false))
     (fn [_this ch modifiers]
       (if char-typed-fn
         (do (char-typed-fn ch) true)
         false))
     (fn [_this mouse-x mouse-y button]
       (try
         (boolean (click-fn mouse-x mouse-y))
         (catch Exception e
           (log/error (str "Error handling hosted screen click " title) e)
           false)))
     (fn [_this]
       (when close-fn (close-fn))))))

(defn open-managed-screen!
  "Open a content-owned hosted screen by opaque screen key and payload."
  [screen-key payload]
  (let [payload* (augment-payload-owner payload)
        captured-session-id (:client-session-id payload*)
        result (with-client-session captured-session-id
                 #(client-ui/client-open-managed-screen! screen-key payload*))]
    (when (= (:command result) :open-screen)
      (let [^Minecraft mc (Minecraft/getInstance)
            title (or (:title result) "Managed Screen")
            char-typed-fn (when (:char-typed? result)
                            (fn [ch]
                              (with-client-session captured-session-id
                                #(client-ui/client-handle-managed-screen-char-typed! screen-key ch))))]
        (.setScreen mc
                    (doto ^DelegatingScreen (create-host-screen
                            title
                            (fn [mouse-x mouse-y]
                              (client-ui/client-handle-managed-screen-click! screen-key mouse-x mouse-y))
                            (fn [mouse-x mouse-y]
                              (client-ui/client-handle-managed-screen-hover! screen-key mouse-x mouse-y))
                            (fn []
                              (client-ui/client-close-managed-screen! screen-key))
                            char-typed-fn)
                      (.withClientSession captured-session-id)))))))

(defn init! []
  (log/info "Client screen host initialized"))
