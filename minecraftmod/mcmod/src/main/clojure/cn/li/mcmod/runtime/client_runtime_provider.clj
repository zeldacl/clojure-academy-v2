(ns cn.li.mcmod.runtime.client-runtime-provider
  (:require [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.client.content-actions :as actions]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer]
            [cn.li.mcmod.client.texture-registry :as textures]))

(defn runtime-provider [_]
  {:merge-client-bridge! bridge/merge-client-bridge!
   :call-adapter bridge/call-adapter
   :resolve-shader bridge/resolve-shader
   :run-client-tick-hooks! actions/run-client-tick-hooks!
   :register-default-renderer-init-fns! render-init/register-default-renderer-init-fns!
   :register-all-renderers! render-init/register-all-renderers!
   :get-scripted-tile-renderer tesr/get-scripted-tile-renderer
   :scripted-renderers-snapshot tesr/scripted-renderers-snapshot
   :install-pose-ops! pose/install-pose-ops!
   :install-render-buffer-ops! buffer/install-render-buffer-ops!
   :register-texture! textures/register-texture!
   :get-texture-path textures/get-texture-path
   :reset-texture-registry-for-test! textures/reset-texture-registry-for-test!})
