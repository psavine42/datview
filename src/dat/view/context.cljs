(ns dat.view.context
  (:require-macros
    [reagent.ratom :refer [reaction]]
    [cljs.core.async.macros :as async-macros :refer [go go-loop]])
  (:require
    [dat.view.utils :as utils]
    [posh.reagent :as posh]
    [dat.reactor :as reactor]
    [reagent.core :as r]
    [reagent.ratom :as ratom]
    [datascript.core :as d]
    [taoensso.timbre :as log :include-macros true]))


;; ## Context

;; We're going to be re-describing things in terms of context.
;; Context includes configuration and contextual information about where things are.
;; But it is extensible, so we can pass through whatever information we might like about how to render things.

;; All of these should be checked for their semantics on :dat.view.base-context/value etc; Is this the right way to represent these things?

;; Should probably move all of these out to reactions or some such, except for anything that's considered public

(defonce default-base-context (r/atom {}))

(def base-context
  ;; Not sure if this memoize will do what I'm hoping it does (:staying-alive true, effectively)
  (memoize
    (fn [app]
      ;; Hmm... should we just serialize the structure fully?
      ;; Adds complexity around wanting to have namespaced attribute names for everything
      (reaction
        (try
          (:dat.view.base-context/value
            @(utils/safe-pull (:conn app) '[*] [:db/ident :dat.view/base-context]))
          ;; Easter egg:
          ;; A self installing config entity :-) Good pattern?
          (catch :default e
            (log/warn "You don't yet have a :dat.view/base-context setting defined. Creating one.")
            (reactor/dispatch! app [:dat.reactor/local-tx [{:db/ident :dat.view/base-context}]])))))))

(defn update-base-context!
  [app f & args]
  (letfn [(txf [db]
            (apply update
                   (d/pull db '[*] [:db/ident :dat.view/base-context])
                   :dat.view.base-context/value
                   f
                   args))]
    (d/transact! (:conn app) [[:db.fn/call txf]])))

(defn set-base-context!
  [app context]
  (update-base-context! app (constantly context)))


;(defn meta-sig
;  [args-vec]
;  (mapv #(vector % (meta %)) args-vec))
;
;(defn meta-memoize
;  ([f]
;    ;; Don't know if this actually has to be an r/atom; may be more performant for it not to be
;   (meta-memoize f (r/atom {})))
;  ([f cache]
;   (fn [& args]
;     (if-let [cached-val (get @cache (meta-sig args))]
;       cached-val
;       (let [new-val (apply f args)]
;         (swap! cache assoc (meta-sig args) new-val)
;         new-val)))))

;; ### Attribute metadata reactions

(def attribute-schema-reaction
  "Returns the corresponding attr-ident entry from the Datomic schema. Returns full entity ref-attr; Have to path for idents."
  (memoize
    (fn [app attr-ident]
      (if (= attr-ident :db/id)
        (reaction {:db/id nil})
        (utils/safe-pull (:conn app)
                         '[* {:db/valueType [:db/ident]
                              :db/cardinality [:db/ident]
                              :db/unique [:db/ident]
                              :attribute.ref/types [:db/ident]}]
                         [:db/ident attr-ident])))))

;; Another function gives us a version of this that maps properly to idents
(def attribute-signature-reaction
  "Reaction of the pull of a schema attribute, where any ref-attrs to something with any ident entity
  have been replaced by that ident keyword."
  (memoize
    (fn [app attr-ident]
      (let [schema-rx (attribute-schema-reaction app attr-ident)]
        (reaction
          (into {}
                (letfn [(mapper [x]
                          (or (:db/ident x)
                              (and (sequential? x) (map mapper x))
                              x))]
                  (map (fn [[k v]] [k (mapper v)])
                       @schema-rx))))))))



;; This is what does all the work of computing our context for each component
;; XXX Need to think about this a bit more; The way things are going with the context resolution now, this may become more orthogonal
;(def component-context nil)
(def component-context
  "This function returns the component configuration (base-context; should rename) for either an entire render network,
  abstractly, or for a specific component based on a component id (namespaced keyword matching the function to be called)."
  ;(memoize
  (fn component-context*
    ([app]
     (reaction
       ;; Don't need this arity if we drop the distinction between base-context and default-base-context
       (merge
         @default-base-context
         @(base-context app))))
    ([app representation-id]
     (component-context* app representation-id {}))
    ([app representation-id {;; Options, in order of precedence in consequent merging
                             :as local-context
                             :keys [;; When the component is in a scope closed over by some particular attribute:
                                    db.attr/ident]}] ;; db/ident of the attribute; precedence below
     (reaction
       (let [base-context @(component-context app)]
         (merge
           (get-in base-context [:dat.view/base-config representation-id])
           (when ident
             (let [attr-sig @(attribute-signature-reaction app ident)]
               (merge
                 (get-in base-context [:dat.view/card-config (:db/cardinality attr-sig) representation-id])
                 (get-in base-context [:dat.view/value-type-config (:db/valueType attr-sig) representation-id])
                 (get-in base-context [:dat.view/attr-config ident]))))
             ;; Need to also get the value type and card config by the attr-config if that's all that's present; Shouldn't ever
             ;; really need to pass in manually XXX
           local-context
           {::locals local-context}))))))

