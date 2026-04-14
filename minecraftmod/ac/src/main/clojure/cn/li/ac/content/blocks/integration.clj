(ns cn.li.ac.content.blocks.integration
  "Integration blocks content loader - energy converters and external mod support"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.ac.integration.block.energy-converter.init :as ec-init]
            [cn.li.ac.integration.block.energy-converter.gui :as ec-gui]))

(defn- load-integration-blocks! []
  (log/info "Loading integration blocks...")
  (ec-init/load-converters!)
  (log/info "Integration blocks loaded"))

(defn- init-integration-block-definitions! []
  (log/info "Initializing integration block definitions...")
  (ec-init/init-converters!)
  (ec-gui/register-converter-guis!)
  (log/info "Integration block definitions initialized"))

(defonce ^:private integration-blocks-installed? (atom false))

(defn init-integration-blocks!
  []
  (when (compare-and-set! integration-blocks-installed? false true)
    (load-integration-blocks!)
    (init-integration-block-definitions!)
    (log/info "Loaded integration blocks content (4 converters)")))