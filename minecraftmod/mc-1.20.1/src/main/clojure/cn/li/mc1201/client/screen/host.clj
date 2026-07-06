(ns cn.li.mc1201.client.screen.host
  "CLIENT-ONLY generic screen host. Content provides draw ops and interaction handlers.
   Draw-ops rendering delegated to shared engine (cn.li.mc1201.gui.draw_ops)."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.gui.draw-ops :as draw-ops]
            [cn.li.mcmod.hooks.core :as client-ui]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]
           [com.mojang.blaze3d.platform Window]))


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
  ([title draw-ops-fn click-fn hover-fn close-fn]
   (create-host-screen title draw-ops-fn click-fn hover-fn close-fn nil))
  ([title draw-ops-fn click-fn hover-fn close-fn char-typed-fn]
   (DelegatingScreen.
     (Component/literal title)
     ;; render — each op dispatched through shared draw-ops engine
     (fn [_this ^GuiGraphics graphics mouse-x mouse-y _partial-tick]
       (try
         (when hover-fn (hover-fn mouse-x mouse-y))
         (doseq [op (draw-ops-fn mouse-x mouse-y)]
           (draw-ops/render-op! graphics op))
         (catch Exception e
           (log/error (str "Error rendering hosted screen " title) e))))
     ;; keyPressed
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
     ;; charTyped
     (fn [_this ch modifiers]
       (if char-typed-fn
         (do (char-typed-fn ch) true)
         false))
     ;; mouseClicked
     (fn [_this mouse-x mouse-y button]
       (try
         (boolean (click-fn mouse-x mouse-y))
         (catch Exception e
           (log/error (str "Error handling hosted screen click " title) e)
           false)))
     ;; removed
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
                    (create-host-screen
                      title
                      (fn [mouse-x mouse-y]
                        (let [^Minecraft mc (Minecraft/getInstance)
                              ^Window win (.getWindow mc)
                              w (.getGuiScaledWidth win)
                              h (.getGuiScaledHeight win)]
                          (with-client-session captured-session-id
                            #(client-ui/client-build-managed-screen-draw-ops screen-key mouse-x mouse-y w h))))
                      (fn [mouse-x mouse-y]
                        (with-client-session captured-session-id
                          #(client-ui/client-handle-managed-screen-click! screen-key mouse-x mouse-y)))
                      (fn [mouse-x mouse-y]
                        (with-client-session captured-session-id
                          #(client-ui/client-handle-managed-screen-hover! screen-key mouse-x mouse-y)))
                      (fn []
                        (with-client-session captured-session-id
                          #(client-ui/client-close-managed-screen! screen-key)))
                      char-typed-fn))))))

(defn init! []
  (log/info "Client screen host initialized"))
