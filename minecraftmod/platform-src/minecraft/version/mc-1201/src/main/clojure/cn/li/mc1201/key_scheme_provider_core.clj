(ns cn.li.mc1201.key-scheme-provider-core
  "Shared key state provider implementation for Forge/Fabric.

   Implements mcmod KeySchemeProvider SPI by querying keyboard/mouse state
   through Minecraft window + GLFW. This keeps polling logic in shared
   mc-1.20.1 layer."
  (:require [cn.li.mcmod.util.log :as log]
            )
  (:import [net.minecraft.client Minecraft]))

(defn ^:private get-window-handle
  "Get current Minecraft GLFW window handle."
  []
  (let [mc (Minecraft/getInstance)
        window (.getWindow mc)]
    (.getWindow window)))

(defn ^:private key-down?
  "Query GLFW key state for a keyboard key code.

   Returns true when key is pressed."
  [key-code]
  (try
    (let [window-handle (get-window-handle)]
      (= 1 (org.lwjgl.glfw.GLFW/glfwGetKey window-handle (int key-code))))
    (catch Throwable e
      (log/debug e "Failed to query GLFW key state" {:key-code key-code})
      false)))

(defn ^:private mouse-button-down?
  "Query GLFW mouse button state (GLFW_MOUSE_BUTTON_LEFT=0, _RIGHT=1, ...).

   Returns true when the button is pressed."
  [button-idx]
  (try
    (let [window-handle (get-window-handle)]
      (= 1 (org.lwjgl.glfw.GLFW/glfwGetMouseButton window-handle (int button-idx))))
    (catch Throwable e
      (log/debug e "Failed to query GLFW mouse button state" {:button-idx button-idx})
      false)))

;; key-idx is normally an int GLFW keyboard keycode. Upstream AcademyCraft's
;; two default ability-slot keys are mouse buttons (KeyManager.MOUSE_LEFT/
;; MOUSE_RIGHT) — represented here as the keywords :mouse-left/:mouse-right so
;; callers don't need a parallel query function for the two mouse cases.
(def ^:private mouse-button-keywords
  {:mouse-left 0    ;; GLFW_MOUSE_BUTTON_LEFT
   :mouse-right 1}) ;; GLFW_MOUSE_BUTTON_RIGHT

(def ^:private impl
  {:is-key-down?
   (fn [scheme-name key-idx]
     (cond
       (contains? mouse-button-keywords key-idx)
       (mouse-button-down? (get mouse-button-keywords key-idx))
       (and (= :original scheme-name) (integer? key-idx))
       (key-down? key-idx)
       (integer? key-idx)
       (key-down? key-idx)
       :else
       false))})

(defn get-spi-implementation
  "Returns the SPI implementation map for platform installation."
  []
  impl)

(defn get-spi-implementation
  "Get KeySchemeProvider SPI implementation object for platform installation."
  []
  impl)
