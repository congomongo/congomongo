(ns somnium.congomongo.coerce
  (:use [somnium.congomongo.util :only [defunk]]
        [clojure.contrib.json :only [json-str read-json]]
        [clojure.contrib.def :only [defvar]]
        [clojure.contrib.core :only [seqable?]])
  (:import [clojure.lang IPersistentMap IPersistentVector Keyword]
           [java.util Map List]
           [com.mongodb DBObject BasicDBObject BasicDBList]
           [com.mongodb.gridfs GridFSFile]
           [com.mongodb.util JSON]))

(defvar *keywordize* true
  "Set this to false to prevent coercion from setting string keys to keywords")


;;; Converting data from mongo into Clojure data objects

(defprotocol ConvertibleFromMongo
  (mongo->clojure [o keywordize]))

(defn- assocs->clojure [kvs keywordize]
  ;; Taking the keywordize test out of the fn reduces derefs
  ;; dramatically, which was the main barrier to matching pure-Java
  ;; performance for this marshalling
  (reduce (if keywordize
            (fn [m [#^String k v]]
              (assoc m (keyword k) (mongo->clojure v true)))
            (fn [m [#^String k v]]
              (assoc m k (mongo->clojure v false))))
          {} kvs))


(extend-protocol ConvertibleFromMongo
  Map
  (mongo->clojure [#^Map m keywordize]
                  (assocs->clojure (.entrySet m) keywordize))

  List
  (mongo->clojure [#^List l keywordize]
                  (vec (map #(mongo->clojure % keywordize) l)))

  Object
  (mongo->clojure [o keywordize] o)

  nil
  (mongo->clojure [o keywordize] o)

  BasicDBList 
  (mongo->clojure [#^BasicDBList l keywordize]
                  (vec (map #(mongo->clojure % keywordize) l)))
  
  DBObject
  (mongo->clojure [#^DBObject f keywordize]
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
  (clojure->mongo [#^Keyword o] (.getName o))

  List
  (clojure->mongo [#^List o] (map clojure->mongo o))

  Object
  (clojure->mongo [o] o)

  nil
  (clojure->mongo [o] o))



(defunk coerce
  {:arglists '([obj [:from :to] {:many false}])
   :doc
   "takes an object, a vector of keywords:
    from [ :clojure :mongo :json ]
    to   [ :clojure :mongo :json ],
    and an an optional :many keyword parameter which defaults to false"}
  [obj from-to :many false]
  (if (= (from-to 0) (from-to 1))
      obj
      (let [fun
            (condp = from-to
              [:clojure :mongo  ] clojure->mongo
              [:clojure :json   ] json-str
              [:mongo   :clojure] #(mongo->clojure #^DBObject % #^Boolean/TYPE *keywordize*)
              [:mongo   :json   ] #(.toString #^DBObject %)
              [:json    :clojure] #(read-json % *keywordize*)
              [:json    :mongo  ] #(JSON/parse %)
              :else               (throw (RuntimeException.
                                          "unsupported keyword pair")))]
        (if many (map fun (if (seqable? obj)
                            obj
                            (iterator-seq obj))) (fun obj)))))

(defn coerce-fields
  "only used for creating argument object for :only"
  [fields]
  (clojure->mongo #^IPersistentMap (zipmap fields (repeat 1))))

