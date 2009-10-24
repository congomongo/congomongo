(ns somnium.congomongo.coerce
  (:use     [somnium.congomongo.config
             :only [*mongo-config*]]
            [somnium.congomongo.util
             :only [named case partition-map map-keys]])
  (:require [clojure.walk :as walk])
  (:import  [com.mongodb BasicDBObject ObjectId]
            [com.mongodb.util JSON]))

;; consider keyword arguments for from and to, there
;; could be some coercions that should only be one-way
;; (eg. keywords to strings but not vice-versa)

(defmacro defcoercion
  "defines a coercion operation with :to and/or :from
   clauses, as in to-database and from-database, for example: 
   (defcoercion :swap-case [arg]
    :to   (string? arg) (.toUpperCase arg)
    :from (string? arg) (.toLowerCase arg)"
  {:arglists '[keyword arg :to test then :from test then]}
  [key arg & kwargs]
  (let [argsym  (if (coll? arg) (first arg) arg)
        clauses (partition-map kwargs 3)]
    `(do
       ~(if-let [to (clauses :to)]
          `(defmethod coerce-object-to-db ~key [_# ~argsym]
             (if ~@to ~argsym)))
       ~(if-let [from (clauses :from)]
          `(defmethod coerce-object-from-db ~key [_# ~argsym]
             (if ~@from ~argsym))))))

;; not exactly sure where these should go, putting them here
;; as they get used here, but should really appear in api

(def query-operators
     {'<      "$lt"
      '<=     "$lte"
      '>      "$gt"
      '>=     "$gte"
      '!=     "$ne"
      'in     "$in"
      '!in    "$nin"
      'mod    "$mod"
      'all    "$all"
      'size   "$size"
      'exists "$exists"
      'where  "$where"})

(defmulti coerce-object-to-db   (fn [k & _] k))
(defmulti coerce-object-from-db (fn [k & _] k))

;; coerces any keyword to string on insert
;; coerces string keys on maps to keywords on fetch

(defcoercion :keywords [x]
  :to   (keyword? x) (name x)
  :from (map? x)     (map-keys #(if (string? %) (keyword %) %) x))

;; coerces query shortcuts to their mongo string versions

(defcoercion :query-operators [x]
  :to (symbol? x) (query-operators x))

;; when it finds a key that begins with _ and ends with id it coerces
;; it to an instance of ObjectId.

(defcoercion :object-ids [x]
  :to (map? x) (let [ks  (keys x)
                     ids (filter #(and (re-matches #"^_.*id$" (named %))
                                       (string? (x %))) ks)]
                 (if (empty? ids) x
                     (apply merge x
                            (for [k ids]
                              {k (ObjectId. #^String (x k))})))))

;; coerces strings in keys with _id or _..._id to ObjectId objects on insert
;; coerces ObjectIds to strings on fetch
;; on-hold: not sure when this will actually be necessary

;; (defcoercion :object-ids [x] ...)

(defn- compose-coercion [f keywords]
  (apply comp (map #(partial f %) keywords)))

(defn cache-coercions! []
  "composes and caches the coercions set in *mongo-config*"
  (let [funs [[:coerce-to-function
               coerce-object-to-db
               (:coerce-to @*mongo-config*)]
              [:coerce-from-function
               coerce-object-from-db
               (:coerce-from @*mongo-config*)]]]
    (doseq [[kw f kwargs] funs]
      (let [fun    (compose-coercion f kwargs)
            walker (fn [x] (walk/prewalk fun x))]
        (swap! *mongo-config*
               merge {kw walker})))))

(defn map-to-object [map]
  (doto (BasicDBObject.)
    (.putAll #^java.util.Map
             ((:coerce-to-function @*mongo-config*) map))))

(defn object-to-map [obj]
  ((:coerce-from-function @*mongo-config*)
   (into {} #^java.util.Map obj)))

(defn object-to-json [obj]
  (JSON/serialize #^BasicDBObject obj))

(defn coerce-many [coll from to]
  (case [from to]
          [:clojure :db] (map map-to-object coll)
          [:db :clojure] (map object-to-map coll)
          [:db :json]    (map #(JSON/serialize #^BasicDBObject %) coll)
          [:json :db]    (map #(JSON/parse #^String %) coll)))

(defn coerce-fields [fields]
  (map-to-object (zipmap fields (repeat 1))))