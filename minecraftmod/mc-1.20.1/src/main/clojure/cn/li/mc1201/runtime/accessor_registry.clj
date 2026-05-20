(ns cn.li.mc1201.runtime.accessor-registry
  "Explicit bootstrap for built-in mc1201 runtime accessor declarations."
  (:require [cn.li.mc1201.runtime.accessor-registry-world :as world]
            [cn.li.mc1201.runtime.accessor-registry-entity :as entity]
            [cn.li.mc1201.runtime.accessor-registry-render :as render]
            [cn.li.mc1201.runtime.accessor-registry-lifecycle :as lifecycle]))

(defn init-default-accessors!
  "Register all built-in accessor declarations.

  Requiring this namespace only loads declarations; registration happens when
  this function is explicitly invoked by platform bootstrap."
  []
  (world/register-world-accessors!)
  (entity/register-entity-accessors!)
  (render/register-render-accessors!)
  (lifecycle/register-lifecycle-accessors!)
  nil)
