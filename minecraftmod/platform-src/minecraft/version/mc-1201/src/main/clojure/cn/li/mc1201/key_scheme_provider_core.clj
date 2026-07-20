(ns cn.li.mc1201.key-scheme-provider-core
  "Shared key state provider implementation for Forge/Fabric.

   Implements mcmod KeySchemeProvider SPI by querying keyboard state through
   Minecraft window + GLFW. This keeps polling logic in shared mc-1.20.1 layer."
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
  "Query GLFW key state for a key code.

   Returns true when key is pressed."
  [key-code]
  (try
    (let [window-handle (get-window-handle)]
      (= 1 (org.lwjgl.glfw.GLFW/glfwGetKey window-handle (int key-code))))
    (catch Throwable e
      (log/debug e "Failed to query GLFW key state" {:key-code key-code})
      false)))

(def ^:private impl
  {:is-key-down?
   (fn [scheme-name key-idx]
     (cond
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
