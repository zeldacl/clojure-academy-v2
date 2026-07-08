(ns cn.li.ac.content.blocks.integration
  "Integration blocks content loader - energy converters and external mod support"
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.integration.block.energy-converter.init :as ec-init]
            [cn.li.ac.integration.block.energy-converter.gui-reactive :as ec-gui-reactive]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private integration-block-spec
  {:label :integration
   :init-entries [(fn []
                    (log/info "Loading integration blocks...")
                    (ec-init/load-converters!))
                  (fn []
                    (log/info "Initializing integration block definitions...")
                    (ec-init/init-converters!))
                  ec-gui-reactive/register-converter-guis-reactive!]
   :post-init-entries [(fn []
                         (log/info "Integration block definitions initialized"))
                       (fn []
                         (log/info "Loaded integration blocks content (4 converters)"))]})

(defonce-guard integration-blocks-installed?)

(defn init-integration-blocks!
  []
  (with-init-guard integration-blocks-installed?
    (block-loader/load-block-category! integration-block-spec)))