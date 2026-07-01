(ns cn.li.mcmod.framework
  "Single framework state container.

   Created once per logical side (client/server) at mod entry.
   Holds ALL content registries, runtime services, AND platform adapters.

   All .clj business files read state from this single atom.
   No other def/delay/atom/defonce allowed in business namespaces.

   Thread safety:
     - :registry/* — write-once (init phase), then freeze → lock-free HAMT reads
     - :service/* — runtime read/write via Facade API guard functions
     - :platform/* — write-once (bootstrap), then read-only function map lookups

   Multi-thread iron rules:
     A) Before crossing async boundary, capture atom: (let [fw *framework*] (future ...))
     B) Freeze :registry after content init: (registry/freeze-all! fw)"
  (:require [cn.li.mcmod.aot :as aot]))

(defn create-framework
  "Create a fresh Framework atom. Returns nil during AOT compilation.

   The returned atom is the single source of truth for all system-level state.
   Created once at mod entry, bound to *framework* for the entire lifecycle.

   Sub-namespaces:
     :registry/* — static content registries (blocks, items, entities, particles, etc.)
                   Populated during content init, frozen afterwards.
     :service/* — runtime dynamic services (lifecycle callbacks, ability-runtime)
                  Read/write during gameplay via service API.
     :platform/* — platform adapter function maps (world ops, nbt ops, etc.)
                   Installed at bootstrap, read-only thereafter."
  []
  (when-not (aot/compiling?)
    (atom {:registry {:blocks      {}
                      :items       {}
                      :entities    {}
                      :fluids      {}
                      :effects     {}
                      :sounds      {}
                      :particles   {}
                      :loot        {}
                      :configs     {}
                      :guis        {}
                      :slots       {}
                      :tiles       {}
                      :tile-kinds  {}
                      :hooks       {}
                      :handlers    {}
                      :commands    {}
                      :energy      {}
                      :providers   {}
                      :keybinds    {}
                      :messages    {}
                      :integrations {}}
           :service {:lifecycle {:content-init-fn nil
                                 :runtime-content-activation-fn nil
                                 :datagen-metadata-init-fns []
                                 :client-init-fns []
                                 :post-spi-client-init-fns []}}
           :platform {}})))

(def ^:dynamic *framework*
  "Single dynamic var for lifecycle-wide Framework access.

   Replaces ~45 individual *-runtime* dynamic vars + ~32 platform SPI dynamic vars.

   Thread safety: this var is based on JVM ThreadLocal. Before crossing async
   boundaries (future, Netty callbacks, enqueueWork), capture the atom instance:
     (let [fw *framework*]
       (future (get-in @fw [:registry :blocks id])))

   Never deref *framework* directly on async threads — it will be nil."
  nil)
