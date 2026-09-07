(ns cn.li.node.flow
  "Descriptor metadata (id + shape, no execution) for the structural
   primitive ids every domain VM's EDN vocabulary treats as reserved:
   sequence, branch, foreach, one local bind, and a terminal marker.

   This namespace used to also carry a real, callable execution engine for
   these ids (execute!/run-sequence/run-branch/...), on the theory that
   combat-core and vfx-core would dispatch through it instead of each
   implementing their own :flow/sequence etc. case branch. Neither ever
   did -- each domain V3 runtime owns its inline handling (with real,
   differences: combat's :flow/foreach reads :limit as a raw field with a
   contracts/budgets-derived default and doesn't restore locals after the
   loop; this namespace's old run-foreach resolved :limit and always
   restored). The execution half was call-graph-dead in production (only
   its own test exercised it) and has been removed; builtin-descriptors
   below is real, live metadata -- combat-core/vocabulary.clj folds it into
   every node environment so :flow/sequence et al validate and expand
   structurally the same way any other component id does, even though each
   domain VM supplies its own :impl-equivalent at dispatch time.")

(def builtin-ids #{:flow/sequence :flow/branch :flow/foreach :data/bind :flow/finish})

(def builtin-descriptors
  "Immutable descriptor values for the shared structural primitive ids. No
   :impl -- see the namespace docstring for why execution isn't here;
   :execution {:kind :structural} is metadata-only (no :capability), just
   enough to satisfy cn.li.node.descriptor/normalize's requirement that
   every :layer :primitive descriptor declare :impl or :execution."
  [{:id :flow/sequence :revision 1 :layer :primitive
    :doc "Run each step in order; a step that finishes the program short-circuits the rest."
    :category :flow
    :children {:steps {:kind :seq :flow :sequential}}
    :effects #{:mutate}
    :execution {:kind :structural}}
   {:id :flow/branch :revision 1 :layer :primitive
    :doc "Run :then when :when is true, otherwise :else."
    :category :flow
    :inputs {:when {:type :boolean}}
    :children {:then {:kind :single :flow :branch} :else {:kind :single :flow :branch}}
    :effects #{:mutate}
    :execution {:kind :structural}}
   {:id :flow/foreach :revision 1 :layer :primitive
    :doc "Run :body once per item in :items (up to :limit), binding :as (and optionally :index-as) inside the body's own closed scope."
    :category :flow
    :inputs {:items {:type [:list-of :any]}
             :as {:type :keyword}
             :index-as {:type :keyword :default nil}
             :limit {:type :long :default nil}}
    :children {:body {:kind :single :flow :closed}}
    :effects #{:mutate}
    :binds-locals #{:as :index-as}
    :execution {:kind :structural}}
   {:id :data/bind :revision 1 :layer :primitive
    :doc "Bind :value to local name :to."
    :category :flow
    :inputs {:to {:type :keyword} :value {:type :any}}
    :effects #{:mutate}
    :binds-locals #{:to}
    :execution {:kind :structural}}
   {:id :flow/finish :revision 1 :layer :primitive
    :doc "Mark the program finished with :outcome; nothing after it runs."
    :category :flow
    :inputs {:outcome {:type :keyword} :finish-ability? {:type :boolean :default false}}
    :effects #{:mutate}
    :execution {:kind :structural}}])
