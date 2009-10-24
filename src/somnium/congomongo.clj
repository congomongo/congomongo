;; Copyright (c) Andrew Boekhoff. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;; can be found in the file epl-v10.html at the root of this distribution. By
;; using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

(ns somnium.congomongo
  (:use     [somnium.congomongo.config
             :only [*mongo-config*]]
            [somnium.congomongo.coerce
             :only [object-to-map map-to-object coerce-many
                    coerce-fields cache-coercions!]]
            [somnium.congomongo.util
             :only [named]])  
  (:import  [com.mongodb Mongo DB DBCollection BasicDBObject]
            [com.mongodb.util JSON]))

(defn mongo!
  "Connects to Mongo, opens a database, and compiles coercions.
   Keyword arguments include:
   :host        -> defaults to localhost
   :port        -> defaults to 27017
   :db          -> must be specified
   :coerce-to   -> specifies coercions on objects going into database,
                   defaults to [:keywords :query-operators :object-ids
   :coerce-from -> specifies coercions on objects coming from the database,
                   defaults to [:keywords]"
  [& options]
  (let [argmap (apply hash-map options)
        mongo  (Mongo. (or (argmap :host) "localhost")
                       (or (argmap :port) 27017))
        db     (.getDB mongo (named (argmap :db)))]
    (do
      (swap! *mongo-config*
                (fn [& _]
                  {:mongo       mongo
                   :db          db
                   :coerce-to   (or (argmap :coerce-to)
                                    [:keywords :query-operators :object-ids])
                   :coerce-from (or (argmap :coerce-from)
                                    [:keywords])}))
      (cache-coercions!))))

;; todo database manipulations

(defn collections
  "Returns the set of collections stored in the current database"
  []
  (.getCollectionNames #^DB (:db @*mongo-config*)))

(defn get-coll
  "Returns a DBCollection object"
  [collection]
  (.getCollection #^DB (:db @*mongo-config*)
                  #^String (named collection)))

(defn drop-coll!
  [collection]
  "Permanently deletes a collection. Use with care."
  (.drop #^DBCollection (.getCollection #^DB (:db @*mongo-config*)
                                        #^String (named collection))))

;; could be cleaner as multimethod dispatching on :many :one :count or
;; simiilar, need to add :skip and :limit options

(defn fetch [col & options]
  "Fetches objects from a collection. Optional arguments include
   :where -> takes a query map
   :only  -> takes an array of keys to retrieve
   :as    -> defaults to :clojure, specify :json to get records as json
   :one   -> defaults to false, use fetch-one as a shortcut
   :count -> defaults to false, use fetch-count as a shortcut"
  (let [argmap (apply hash-map options)
        where  #^BasicDBObject (map-to-object (or (:where argmap) {}))
        only   #^BasicDBObject (coerce-fields (or (:only argmap) []))
        as     (or (:as argmap) :clojure)
        col    #^DBCollection (get-coll col)]
    (cond
      (:count argmap) (.getCount col where only)
      (:one   argmap) (let [#^BasicDBObject one (.findOne col where only)]
                        (if (= :clojure as) (object-to-map one) (JSON/serialize one)))
      :else           (if-not (argmap :one)
                        (-> (.find col where only)
                            (coerce-many :db as))))))

(defn fetch-one [col & kwargs]
  (apply fetch col (concat kwargs [:one true])))

(defn fetch-count [col & kwargs]
  (apply fetch col (concat kwargs [:count true])))

(defn insert! [col map]
  "Inserts a map into collection. Will not overwrite existing maps."
  (.insert #^DBCollection  (get-coll col)
           #^BasicDBObject (map-to-object map)))

(defn mass-insert! [col mapseq]
  "Inserts a lot of maps into a collection. Does not overwrite existing objects."
  (.insert #^DBCollection (get-coll col)
           #^java.util.List (coerce-many mapseq :clojure :db)))

;; should this raise an exception if _ns and _id aren't present?
(defn update!
  "Alters/inserts a map in a collection. Overwrites existing objects.
  The shortcut forms need a map with valid :_id and :_ns fields or
  a collection and a map with a valid :_id field."
  ([map]
     (update! (:_ns map) {:_id (:_id map)} map))
  ([col map]
     (update! col {:_id (:_id map)} map))
  ([col old new]
     (.update #^DBCollection  (get-coll col)
              #^BasicDBObject (map-to-object old)
              #^BasicDBObject (map-to-object new)
              true true)))

(defn destroy!
  "Removes map from collection. Takes a collection name and
   a query map argument constraints."
  ([coll map] (.remove (get-coll coll) #^BasicDBObject (map-to-object map))))



