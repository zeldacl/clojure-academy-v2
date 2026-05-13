(ns cn.li.mc1201.client.render.script-render-runtime-cache
  "Compatibility cache facade for ScriptRender runtime.

  Delegates to `script-render-runtime` to keep API stable while preserving the
  current implementation split." 
  (:require [cn.li.mc1201.client.render.script-render-runtime :as runtime]))

(defn clear-cache!
  []
  (runtime/clear-cache!))

(defn rebuild-cache!
  []
  (runtime/rebuild-cache!))

(defn cache-size
  []
  (runtime/cache-size))

(defn get-draw-plan
  [renderer-id]
  (runtime/get-draw-plan renderer-id))

(defn draw-plan-kind
  [renderer-id]
  (runtime/draw-plan-kind renderer-id))

(defn use-scripted-renderer?
  [renderer-id render-kind]
  (runtime/use-scripted-renderer? renderer-id render-kind))
