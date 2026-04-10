(ns cn.li.forge1201.gui.cgui-runtime
  "Client runtime facade for CGUI.

  This namespace is safe to load during checkClojure. The heavy client implementation
  lives in cn.li.forge1201.gui.cgui-runtime-impl and is loaded lazily on first use."
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private runtime-impl (atom nil))
(defonce ^:private runtime-loader
  (delay
    (require 'cn.li.forge1201.gui.cgui-runtime-impl)
    true))

(defn register-impl!
  "Install concrete runtime implementation map from cgui-runtime-impl."
  [impl]
  (reset! runtime-impl impl)
  nil)

(defn- impl-fn
  [k]
  (when (nil? @runtime-impl)
    @runtime-loader)
  (or (get @runtime-impl k)
      (throw (ex-info "CGUI runtime implementation not installed"
                      {:missing k})) ))

(defn resize-root!
  [root width height]
  ((impl-fn :resize-root!) root width height))

(defn frame-tick!
  [root event]
  ((impl-fn :frame-tick!) root event))

(defn render-tree!
  [gg root left top]
  ((impl-fn :render-tree!) gg root left top))

(defn mouse-click!
  [root mx my left top button]
  ((impl-fn :mouse-click!) root mx my left top button))

(defn mouse-drag!
  [root mx my left top]
  ((impl-fn :mouse-drag!) root mx my left top))

(defn key-input!
  [root key-code scan-code typed-char]
  ((impl-fn :key-input!) root key-code scan-code typed-char))

(defn dispose!
  [root]
  ((impl-fn :dispose!) root))
