(ns somnium.congomongo.coerce
  (:use [somnium.congomongo.util :only [defunk]]
        [clojure.contrib.json read write]
        [clojure.contrib.def :only [defvar]])
  (:import [somnium.congomongo ClojureDBObject]
           [clojure.lang IPersistentMap]
           [com.mongodb.util JSON]))

(defvar *keywordize* true
  "Set this to false to prevent ClojureDBObject from setting string keys to keywords")

(defunk 
  coerce
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
              [:clojure :mongo  ] #(ClojureDBObject. #^IPersistentMap %)
              [:clojure :json   ] #(json-str %)
              [:mongo   :clojure] #(.toClojure #^ClojureDBObject %
                                               #^Boolean/TYPE *keywordize*)
              [:mongo   :json   ] #(.toString #^ClojureDBObject %)
              [:json    :clojure] #(binding [*json-keyword-keys* *keywordize*] (read-json %))
              [:json    :mongo  ] #(JSON/parse %)
              :else               (throw (RuntimeException.
                                          "unsupported keyword pair")))]
        (if many (map fun obj) (fun obj)))))

(defn coerce-fields
  "only used for creating argument object for :only"
  [fields]
  (ClojureDBObject. #^IPersistentMap (zipmap fields (repeat 1))))