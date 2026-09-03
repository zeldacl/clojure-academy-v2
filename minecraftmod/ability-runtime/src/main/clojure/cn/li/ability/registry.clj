(ns cn.li.ability.registry
  "Merges N content bundles (each produced by cn.li.ability.compose/
   compose-catalog) into one frozen, read-only value. Built once at
   bootstrap; every per-tick and per-frame read is a plain map lookup on an
   immutable value -- no atom deref, no lock, no allocation.

   Node environments are NOT merged into one. cn.li.node.environment/build
   rejects duplicate descriptor ids, and a generic id like :combat/damage
   legitimately appears in every tenant's environment -- it comes from
   cn.li.combat.vocabulary, the SAME value, supplied to every bundle by its
   own compose-catalog call. Merging would either throw on that legitimate
   overlap or need dedup-by-equality, which cannot work on maps holding
   :impl function objects. Shared base + per-tenant overlay instead: each
   tenant's own node-environment is kept as-is; descriptor-in below checks
   the tenant's own environment first, falling back to any OTHER tenant's
   only if truly needed (today it never is -- every tenant's environment
   already has the full shared vocabulary baked in by compose-catalog).

   Ability/effect ids are NOT required to be namespaced by content-id.
   ac's real ability ids and effect ids are bare keywords with no namespace at all
   -- a hard content-id-prefix requirement would reject every single one
   of them, forcing a content-data migration this module has no business
   requiring. What actually matters for multi-tenancy is detecting a
   genuine COLLISION: two different content modules registering the same
   id. That is what add-bundle checks, regardless of whether either id
   happens to be namespaced."
  (:require [cn.li.node.api :as node-api]))

(defn empty-registry [] {:bundles {} :frozen? false})

(defn add-bundle
  "registry' with `bundle` (one cn.li.ability.compose/compose-catalog
   result) added. Pure; throws on a duplicate content-id or an ability/
   effect id already claimed by a different content-id already in the
   registry, naming both owners."
  [registry {:keys [content-id combat vfx] :as bundle}]
  (when (:frozen? registry)
    (throw (ex-info "registry is frozen" {:content-id content-id})))
  (when-not (keyword? content-id)
    (throw (ex-info "bundle content-id must be a keyword" {:bundle bundle})))
  (when (contains? (:bundles registry) content-id)
    (throw (ex-info "duplicate content-id" {:content-id content-id})))
  (let [existing-ability-owners (:ability-owners registry {})
        existing-effect-owners (:effect-owners registry {})
        ability-ids (map :id (:registrations combat))
        effect-ids (keys (:effects vfx))
        claim (fn [owners ids kind]
                (reduce (fn [owners id]
                          (when-let [owner (get owners id)]
                            (throw (ex-info (str kind " id collision between content modules")
                                            {:id id :owners [owner content-id]})))
                          (assoc owners id content-id))
                        owners ids))]
    (-> registry
        (assoc-in [:bundles content-id] bundle)
        (assoc :ability-owners (claim existing-ability-owners ability-ids :ability))
        (assoc :effect-owners (claim existing-effect-owners effect-ids :effect)))))

(defn freeze
  "The immutable, queryable registry value every per-tick/per-frame read
   goes through. bundles is kept for content-id enumeration and per-tenant
   hashing; abilities/effects are flattened across all tenants for O(1)
   lookup by id (safe: add-bundle already proved no id collides)."
  [registry]
  (let [bundles (:bundles registry)]
    {:frozen? true
     :content-ids (vec (sort (keys bundles)))
     :bundles bundles
     :abilities (into {} (mapcat (fn [[_ b]] (map (juxt :id identity) (:registrations (:combat b))))) bundles)
     :effects (into {} (mapcat (fn [[_ b]] (:effects (:vfx b)))) bundles)
     :hashes (into (sorted-map) (map (fn [[cid b]] [cid (:content-hash b)])) bundles)}))

(defn bundle-for [registry content-id] (get-in registry [:bundles content-id]))
(defn content-ids [registry] (:content-ids registry))
(defn ability [registry ability-id] (get-in registry [:abilities ability-id]))
(defn effect [registry effect-id] (get-in registry [:effects effect-id]))

(defn content-hash
  "One deterministic SHA-256 hash over every tenant's own content-hash
   (cn.li.node.digest, not clojure.core/hash -- see the P2.4 refactor
   commit for why that distinction matters for anything crossing the
   network), so a client/server mismatch can be root-caused to which
   content module diverged instead of just failing one combined hash."
  [registry]
  (node-api/content-hash (:hashes registry)))

(defn descriptor-in
  "Tenant-scoped node descriptor lookup, for compile-time composite
   expansion. Each tenant's own node-environment (built by its own
   compose-catalog call) already has the full shared vocabulary folded in,
   so this is just that tenant's environment -- no cross-tenant fallback is
   needed today; falls back to another tenant's only as a last resort,
   should a future content module ever legitimately reference a sibling's
   descriptor."
  [registry content-id id]
  (let [own (get-in registry [:bundles content-id :node-environment :descriptors id])]
    (or own
        (some (fn [[_ bundle]] (get-in bundle [:node-environment :descriptors id]))
              (:bundles registry)))))
