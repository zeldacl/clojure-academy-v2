(ns cn.li.ac.core.platform-bootstrap
  "Compatibility facade for AC bootstrap wiring.

  Implementations are split by responsibility under
  cn.li.ac.core.platform-bootstrap.{base,gui,runtime}."
  (:require [cn.li.ac.core.platform-bootstrap.base :as bootstrap-base]
            [cn.li.ac.core.platform-bootstrap.gui :as bootstrap-gui]
            [cn.li.ac.core.platform-bootstrap.runtime :as bootstrap-runtime]))

(def bind-mod-id! bootstrap-base/bind-mod-id!)
(def init-world-data! bootstrap-base/init-world-data!)
(def init-configs! bootstrap-base/init-configs!)

(def register-slot-validators! bootstrap-gui/register-slot-validators!)
(def register-gui-platform-impl! bootstrap-gui/register-gui-platform-impl!)

(def install-java-api-bridges! bootstrap-runtime/install-java-api-bridges!)
(def install-platform-bridges! bootstrap-runtime/install-platform-bridges!)
(def register-network-helper-fns! bootstrap-runtime/register-network-helper-fns!)
(def register-datagen-metadata! bootstrap-runtime/register-datagen-metadata!)
