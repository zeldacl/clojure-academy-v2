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

(def *framework*
  "Single var for lifecycle-wide Framework access.

   INITIALIZED TO nil — the real atom is injected at runtime via alter-var-root.
   Keeping nil during AOT compilation prevents any compile-time atom instantiation,
   macro expansion, or classloader pollution.

   At startup, the platform entry point calls:
     (alter-var-root #'*framework* (constantly (atom full-state)))

   No ^:dynamic — every thread sees the same atom via direct var deref.
   Zero ThreadLocal overhead, zero async NPE on ForkJoinPool.

   Tests inject mock instances via alter-var-root in setup."
  nil)

(defn create-framework
  "Create a fresh Framework atom with the full initial state.
   Returns nil during AOT compilation — the real atom is created
   at runtime and injected into *framework* via alter-var-root."
  []
  (when-not (aot/compiling?)
    (atom
      {:registry {:blocks      {}
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

(defn fw-atom
  "Return the current Framework atom.
   Always returns a valid atom — the empty initial atom before startup,
   or the fully-populated atom after create-framework completes."
  ^clojure.lang.IAtom
  []
  *framework*)
