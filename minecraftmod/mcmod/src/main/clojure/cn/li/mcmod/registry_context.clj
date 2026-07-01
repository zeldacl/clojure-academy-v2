(ns cn.li.mcmod.registry-context
  "Unified content registry context passed through the mod lifecycle.

  All DSL registries (block/item/entity/fluid/effect/etc.) write into this
  context during content-init, and query from it at runtime.

  This eliminates the ~50 `defonce installed-*-registry-runtime` singletons
  in favour of a single context map created at the mod entry point and
  threaded through all registration and query functions.")

(defn create-empty
  "Create a fresh, empty registry context.
  Called once at content-init entry."
  []
  {:blocks      {}
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
   :integrations {}})
