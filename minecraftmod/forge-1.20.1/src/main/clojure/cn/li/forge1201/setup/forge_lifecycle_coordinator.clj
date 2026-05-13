(ns cn.li.forge1201.setup.forge-lifecycle-coordinator
  "Stable Forge lifecycle orchestration entrypoint.

  Delegates to lifecycle-init internals while providing a canonical coordinator
  namespace for future Wave-A/Wave-B refactors."
  (:require [cn.li.forge1201.setup.lifecycle-init :as lifecycle-init]))

(def init-lifecycle! lifecycle-init/init-lifecycle!)
(def init-lifecycle-with-error-handling! lifecycle-init/init-lifecycle-with-error-handling!)