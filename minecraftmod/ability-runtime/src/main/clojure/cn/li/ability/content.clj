(ns cn.li.ability.content
  "Immutable content-pack contract shared by AC and future BC/CC packs.

   The pack contains application policy and resource roots, not a runtime
   singleton.  ability-runtime composes one Mod-level bundle from these
   values and freezes it before any player input is accepted.")

(defn- fail [message data]
  (throw (ex-info message data)))

(defn validate-pack
  [pack]
  (when-not (map? pack)
    (fail "content pack must be a map" {:pack pack}))
  (let [{:keys [content-id resources policies persistence controls]} pack]
    (when-not (keyword? content-id)
      (fail "content pack requires keyword :content-id" {:pack pack}))
    (when-not (map? resources)
      (fail "content pack requires :resources" {:content-id content-id}))
    (when-not (map? policies)
      (fail "content pack requires :policies" {:content-id content-id}))
    (when-not (map? persistence)
      (fail "content pack requires :persistence" {:content-id content-id}))
    (when-not (map? controls)
      (fail "content pack requires :controls" {:content-id content-id}))
    (when-not (every? #(= content-id (some-> % namespace keyword))
                      (keep identity (:ids pack)))
      (fail "content pack ids must be namespaced by :content-id"
            {:content-id content-id :ids (:ids pack)})))
  pack)

(defn compile-bundle
  "Create the immutable content-pack bundle.  Resource loading and catalog
   compilation are injected so this namespace remains Minecraft-free."
  [{:keys [content-packs compile-resources compile-catalog]}]
  (when-not (ifn? compile-resources)
    (fail "compile-bundle requires :compile-resources" {}))
  (when-not (ifn? compile-catalog)
    (fail "compile-bundle requires :compile-catalog" {}))
  (let [packs (mapv validate-pack (or content-packs []))
        ids (mapv :content-id packs)]
    (when-not (= (count ids) (count (distinct ids)))
      (fail "duplicate content pack id" {:ids ids}))
    (let [resources (mapv compile-resources packs)
          catalog (compile-catalog packs resources)]
      {:content-packs packs
       :resources resources
       :catalog catalog
       :content-ids (set ids)})))

