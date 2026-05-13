(ns cn.li.ac.core.init
  "AC core initialization orchestration extracted from cn.li.ac.core."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.core.platform-bootstrap :as bootstrap]
            [cn.li.mcmod.util.log :as log]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (bootstrap/bind-mod-id!)
  (bootstrap/register-slot-validators!)
  (bootstrap/register-gui-platform-impl!)
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  (bootstrap/init-world-data!)
  (bootstrap/install-java-api-bridges!)
  (bootstrap/install-platform-bridges!)
  (bootstrap/register-network-helper-fns!)
  (bootstrap/register-datagen-metadata!)
  (bootstrap/init-configs!)
  nil)
