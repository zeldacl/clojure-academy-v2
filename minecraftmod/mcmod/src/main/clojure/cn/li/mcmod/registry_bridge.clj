(ns cn.li.mcmod.registry-bridge
  "Bridge between registry-context and existing defonce-based registries.

  During the Phase 2 transition, content-init creates a registry-context
  and stores it here. Registry query functions check this first, falling
  back to the old defonce-based registry. This allows incremental migration
  without changing every query call site at once.

  In Phase 5, after all registries are migrated, the old defonce singletons
  and this bridge are removed, and query functions take ctx directly.")

(def ^:private registry-ctx (volatile! nil))

(defn set-registry-context!
  "Store the registry context created during content-init.
  Called once by the lifecycle entry point."
  [ctx]
  (vreset! registry-ctx ctx))

(defn get-registry-context
  "Return the current registry context, or nil if not yet initialized."
  []
  @registry-ctx)

(defn reset-for-test!
  "Clear the registry context. For tests only."
  []
  (vreset! registry-ctx nil))
