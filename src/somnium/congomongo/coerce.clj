; Copyright (c) 2009-2012 Andrew Boekhoff, Sean Corfield

; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:

; The above copyright notice and this permission notice shall be included in
; all copies or substantial portions of the Software.

; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
; THE SOFTWARE.

(ns somnium.congomongo.coerce
  (:require [clojure.data.json :refer [write-str read-str]])
  (:import [clojure.lang IPersistentMap Keyword]
           [java.util Map List Set]
           [com.mongodb DBObject BasicDBObject BasicDBList]
           [org.bson.json JsonWriterSettings JsonMode]))

(def ^:private json-settings
  ; Create a settings object to allow us to serialize an object with "RELAXED" JSON.
  ; https://github.com/mongodb/specifications/blob/df6be82f865e9b72444488fd62ae1eb5fca18569/source/extended-json.rst
  (-> (JsonWriterSettings/builder)
      (.outputMode JsonMode/RELAXED)
      (.build)))

(def ^{:dynamic true
       :doc "Set this to false to prevent coercion from setting string keys to keywords"
       :tag 'boolean}
      *keywordize* true)

;; seqable? is present in Clojure 1.9.0
(if-let [ccs (resolve 'clojure.core/seqable?)]
  (def ^:private seqable'? ccs)
  (defn- seqable'?
    "Returns true if (seq x) will succeed, false otherwise.
    Present to support pre-Clojure 1.9.0 Alpha 5 without needing
    to depend on clojure.core.incubator."
    [x]
    (or (seq? x)
        (instance? clojure.lang.Seqable x)
        (nil? x)
        (instance? Iterable x)
        (.isArray (.getClass ^Object x))
        (string? x)
        (instance? java.util.Map x))))

(defn json->mongo [^String s]
  (BasicDBObject/parse s))

(defn ^String mongo->json [^BasicDBObject dbo]
  (.toJson dbo json-settings))


;;; Converting data from mongo into Clojure data objects

(defprotocol ConvertibleFromMongo
  (mongo->clojure [o keywordize]))

(defn- assocs->clojure [kvs keywordize]
  ;; Taking the keywordize test out of the fn reduces derefs
  ;; dramatically, which was the main barrier to matching pure-Java
  ;; performance for this marshalling
  (reduce (if keywordize
            (fn [m [^String k v]]
              (assoc m (keyword k) (mongo->clojure v true)))
            (fn [m [^String k v]]
              (assoc m k (mongo->clojure v false))))
          {} (reverse kvs)))

(extend-protocol ConvertibleFromMongo
  Map
  (mongo->clojure [^Map m keywordize]
                  (assocs->clojure (.entrySet m) keywordize))

  List
  (mongo->clojure [^List l keywordize]
                  (vec (map #(mongo->clojure % keywordize) l)))

  Object
  (mongo->clojure [o keywordize] o)

  nil
  (mongo->clojure [o keywordize] o)

  BasicDBList
  (mongo->clojure [^BasicDBList l keywordize]
                  (vec (map #(mongo->clojure % keywordize) l)))

  DBObject
  (mongo->clojure [^DBObject f keywordize]
                  ;; DBObject provides .toMap, but the implementation in
                  ;; subclass GridFSFile unhelpfully throws
                  ;; UnsupportedOperationException
                  (assocs->clojure (for [k (.keySet f)] [k (.get f k)]) keywordize)))


;;; Converting data from Clojure into data objects suitable for Mongo

(defprotocol ConvertibleToMongo
  (clojure->mongo [o]))

(extend-protocol ConvertibleToMongo
  IPersistentMap
  (clojure->mongo [m] (let [dbo (BasicDBObject.)]
                        (doseq [[k v] m]
                          (.put dbo
                                (clojure->mongo k)
                                (clojure->mongo v)))
                        dbo))

  Keyword
  (clojure->mongo [^Keyword o] (let [o-ns (namespace o)]
                                 (str o-ns (when o-ns "/") (name o))))

  List
  (clojure->mongo [^List o] (map clojure->mongo o))

  Set
  (clojure->mongo [^Set o] (set (map clojure->mongo o)))

  Object
  (clojure->mongo [o] o)

  nil
  (clojure->mongo [o] o))


(def ^{:dynamic true
       :doc "Mapping of [from to] pairs to translation functions for coerce."}
     *translations* {[:clojure :mongo  ] #'clojure->mongo
                     [:clojure :json   ] #'write-str
                     [:mongo   :clojure] #(mongo->clojure ^DBObject % ^boolean *keywordize*)
                     [:mongo   :json   ] #'mongo->json
                     [:json    :clojure] #(read-str % :key-fn (if *keywordize*
                                                                keyword
                                                                identity))
                     [:json    :mongo  ] #'json->mongo})

(defn coerce
  "takes an object, a vector of keywords:
   from [ :clojure :mongo :json ]
   to   [ :clojure :mongo :json ],
   and an an optional :many keyword parameter which defaults to false"
  {:arglists '([obj [:from :to]] [obj [:from :to] :many many?])}
  [obj from-and-to & {:keys [many] :or {many false}}]
  (let [[from to] from-and-to]
    (cond (= from to) obj
          (nil?   to) nil
          :else       (if-let [f (*translations* from-and-to)]
                        (if many
                          (map f (if (seqable'? obj)
                                   obj
                                   (iterator-seq obj)))
                          (f obj))
                        (throw (RuntimeException. "unsupported keyword pair"))))))

(defn ^DBObject dbobject
   "Create a DBObject from a sequence of key/value pairs, in order."
  [& args]
  (let [dbo (BasicDBObject.)]
    (doseq [[k v] (partition 2 args)]
      (.put dbo
            (clojure->mongo k)
            (clojure->mongo v)))
    dbo))

(defn ^DBObject coerce-fields
  "Used for creating argument object for :only - unordered,
   maps truthy to 1 and falsey to 0, default 1."
  [fields]
  (clojure->mongo ^IPersistentMap (if (map? fields)
                                    (reduce-kv (fn [m k v]
                                                 (assoc m k (if v 1 0)))
                                               {} fields)
                                    (zipmap fields (repeat 1)))))

(defn ^DBObject coerce-ordered-fields
  "Used for creating index specifications and sort specifications.
   Accepts a vector of fields or field/value pairs. Produces an
   ordered object of field/value pairs - default 1."
  [fields]
  (clojure->mongo ^IPersistentMap (apply array-map
                                         (flatten
                                          (for [f fields]
                                            ;; Related to https://jira.mongodb.org/browse/JAVA-2757
                                            ;; Using longs for field specifiers results in dropIndex
                                            ;; determining an incorrect name for the index.
                                            ;;
                                            ;; NOTE: specifiers can be strings for geospatial indices, e.g.
                                            ;; "2d", "2dsphere"
                                            (if (vector? f)
                                              (update-in f [1] (fn [x] (if (number? x) (int x) x)))
                                              [f (int 1)]))))))

(defn ^DBObject coerce-index-fields
  "Used for creating index specifications.
   Deprecated as of 0.3.3.
   [:a :b :c] => (array-map :a 1 :b 1 :c 1)
   [:a [:b 1] :c] => (array-map :a 1 :b -1 :c 1)

   See also somnium.congomongo/add-index!"
  [fields]
  (coerce-ordered-fields fields))
