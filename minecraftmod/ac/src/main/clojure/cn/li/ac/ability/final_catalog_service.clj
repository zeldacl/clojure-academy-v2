(ns cn.li.ac.ability.final-catalog-service
  "Runtime-owned view of the final AC catalog.

   The catalog is assembled once, then each registration is either compiled
   into the final graph IR or retained as an explicit pending migration.  A
   pending registration is not executable and is never delegated to a legacy
   evaluator; this makes the migration state observable while allowing the
   final runtime to be introduced before every source document is lowered.")

(defonce ^:private catalog-state (atom {:status :cold}))

(defn- catalog-api []
  (requiring-resolve 'cn.li.ac.ability.final-catalog/assemble))

(defn- compiler-api []
  (requiring-resolve 'cn.li.combat.final-compiler/compile-program))

(defn- compile-registration [registration]
  (try
    (assoc registration
           :status :ready
           :compiled ((compiler-api) (:graph registration)))
    (catch clojure.lang.ExceptionInfo error
      (assoc registration
             :status :pending-final-node-migration
             :compile-error (ex-data error)))))

(defn initialize!
  "Load and index the immutable final catalog.

   `assemble-options` is forwarded to the pure catalog loader, which keeps
   this service usable in headless tests and deterministic server startup."
  ([] (initialize! {}))
  ([assemble-options]
   (let [assembled ((catalog-api) assemble-options)
         registrations (mapv compile-registration
                             (get-in assembled [:combat :registrations]))
         by-id (into {} (map (juxt :id identity) registrations))
         result (assoc assembled
                       :combat (assoc (:combat assembled)
                                      :registrations registrations
                                      :by-id by-id)
                       :status :ready
                       :ready-count (count (filter #(= :ready (:status %)) registrations))
                       :pending-count (count (filter #(= :pending-final-node-migration (:status %)) registrations)))]
     (reset! catalog-state result)
     result)))

(defn state [] @catalog-state)

(defn registration [id]
  (get-in @catalog-state [:combat :by-id id]))

(defn available? [id]
  (= :ready (:status (registration id))))

(defn program [id]
  (:compiled (registration id)))

(defn vfx-catalog []
  (get-in @catalog-state [:vfx :effects]))

(defn content-hash []
  (:content-hash @catalog-state))

(defn migration-status []
  (select-keys @catalog-state [:status :ready-count :pending-count :content-hash]))

(defn migration-report
  "Return a deterministic audit of every registration and its first final
   compiler failure.  This is intentionally data-only so CI can require the
   pending set to reach zero before old runtime deletion."
  []
  (let [registrations (get-in @catalog-state [:combat :registrations])]
    {:total (count registrations)
     :ready (count (filter #(= :ready (:status %)) registrations))
     :pending (count (filter #(= :pending-final-node-migration (:status %)) registrations))
     :entries (mapv (fn [entry]
                      (select-keys entry [:id :source-id :status :compile-error]))
                    (sort-by (comp str :id) registrations))}))
