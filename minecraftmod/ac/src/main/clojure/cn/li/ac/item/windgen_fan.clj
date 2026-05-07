(ns cn.li.ac.item.windgen-fan
  "Wind generator fan item (required by wind-gen main slot)."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard installed?)

(defn init-windgen-fan!
  []
  (with-init-guard installed?
    (idsl/register-item!
      (idsl/create-item-spec
        "windgen_fan"
        {:max-stack-size 1
         :durability 100
         :creative-tab :misc
            :properties {:tooltip ["Wind generator rotor"
                    "Install into Wind Generator Main"]
                :model-texture "windgen_fan"}}))
          (log/info "Wind generator fan item initialized")))