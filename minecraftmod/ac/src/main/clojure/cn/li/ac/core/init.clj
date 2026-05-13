(ns cn.li.ac.core.init
  "AC core initialization orchestration extracted from cn.li.ac.core."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.core.platform-bootstrap.base :as bootstrap-base]
            [cn.li.ac.core.platform-bootstrap.gui :as bootstrap-gui]
            [cn.li.ac.core.platform-bootstrap.runtime :as bootstrap-runtime]
            [cn.li.mcmod.util.log :as log]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (bootstrap-base/bind-mod-id!)
  (bootstrap-gui/register-slot-validators!)
  (bootstrap-gui/register-gui-platform-impl!)
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  (bootstrap-base/init-world-data!)
  (bootstrap-runtime/install-java-api-bridges!)
  (bootstrap-runtime/install-platform-bridges!)
  (bootstrap-runtime/register-network-helper-fns!)
  (bootstrap-runtime/register-datagen-metadata!)
  (bootstrap-base/init-configs!)
  nil)
