; Copyright (c) 2009 Andrew Boekhoff

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

(ns somnium.congomongo
  (:use     [somnium.congomongo.config
             :only [*mongo-config*]]
            [somnium.congomongo.coerce
             :only [object-to-map map-to-object coerce-many
                    coerce-fields cache-coercions!]]
            [somnium.congomongo.util
             :only [named case]])  
  (:import  [com.mongodb Mongo DB DBCollection BasicDBObject]
            [com.mongodb.util JSON]))

(defn mongo!
  "Creates a Mongo object, opens a database, and compiles coercions.
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
      (reset! *mongo-config*
               {:mongo       mongo
                :db          db
                :coerce-to   (or (argmap :coerce-to)
                                 [:keywords
                                  :query-operators
                                  :object-ids])
                :coerce-from (or (argmap :coerce-from)
                                 [:object-ids
                                  :nested-db-objects])})
      (cache-coercions!)
      true)))

;; perhaps split *mongo-config* out into vars for thread-local
;; changes. 

(defn drop-database!
 "drops a database from the mongo server"
 [title]
 (.dropDatabase (:mongo @*mongo-config*) (named title)))

(defn set-database!
  "atomically alters the current database"
  [title]
  (if-let [db (.getDB (:mongo @*mongo-config*) (named title))]
    (swap! *mongo-config* merge {:db db})
    (throw (RuntimeException. (str "database with title " title " does not exist.")))))

(defn databases
  "List databases on the mongo server" []
  (.getDatabaseNames (:mongo @*mongo-config*)))

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

(defn fetch 
  "Fetches objects from a collection. Optional arguments include
   :where -> takes a query map
   :only  -> takes an array of keys to retrieve
   :as    -> defaults to :clojure, specify :json to get records as json
   :one   -> defaults to false, use fetch-one as a shortcut
   :count -> defaults to false, use fetch-count as a shortcut"
  [col & options]
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

(defn fetch-one [col & options]
  (apply fetch col (concat options [:one true])))

(defn fetch-count [col & options]
  (apply fetch col (concat options [:count true])))

(defn insert! 
  "Inserts a map into collection. Will not overwrite existing maps.
   Takes an optional :return argument that specifies what to return.
   Default is nil, can specify :clojure, :json and :db."
  [col map & options]
  (let [argmap (apply hash-map options)
        res    (.insert #^DBCollection  (get-coll col)
                        #^BasicDBObject (map-to-object map))]
    (let [c (fn [x] (= x (argmap :return)))]
      (cond 
        (c :json)     (JSON/serialize res) 
        (c :clojure)  (object-to-map res)
        (c :db)       res
        (c :else)     nil))))

(defn mass-insert! 
  "Inserts a lot of maps into a collection. Does not overwrite existing objects.
   Returns a lazy sequence of clojure maps. Options:
   :return -> defaults to :clojure, can also be :json, :db, or nil"
  [col mapseq & options]
  (let [argmap (apply hash-map options)
        res    (.insert #^DBCollection (get-coll col)
                 #^java.util.List (coerce-many mapseq :clojure :db))]
    (case (argmap :return)
            :db      res
            :json    (JSON/serialize res)
            :clojure (coerce-many res :db :clojure)
            :else    nil)))

;; should this raise an exception if _ns and _id aren't present?
(defn update!
  "Alters/inserts a map in a collection. Overwrites existing objects.
  The shortcut forms need a map with valid :_id and :_ns fields or
  a collection and a map with a valid :_id field."
  ([obj]
     (update! (obj "_ns")
              {"_id" (obj "_id")}
              obj))
  ([col obj]
     (update! col
              {"_id" (obj "_id")}
              obj))
  ([col old new]
     (object-to-map (.update #^DBCollection  (get-coll col)
                             #^BasicDBObject (map-to-object old)
                             #^BasicDBObject (map-to-object new)
                             true true))))

(defn destroy!
  "Removes map from collection. Takes a collection name and
   a query map argument."
  ([map]
     (.remove
      (get-coll (map "_ns"))
        #^BasicDBObject (map-to-object {"_id" (map "_id")})))
  ([coll map]
     (.remove (get-coll coll)
              #^BasicDBObject (map-to-object map))))

(defn get-indexes
  "Get index information on collection"
  [coll]
  (coerce-many (.getIndexInfo (get-coll coll))
               :db :clojure))

(defn add-index!
  "Adds an index on the collection for the specified fields if it does not exist.
   Options include:
   :unique -> defaults to false
   :force  -> defaults to true"
  [coll fields & options]
  (let [options (apply hash-map options)]
        (-> (get-coll coll)
            (.ensureIndex (coerce-fields fields)
                          (or (options :force) true)
                          (or (options :unique) false)))))

(defn drop-index!
  "Drops an index on the collection for the specified fields"
  [coll fields]
  (.dropIndex (get-coll coll) (coerce-fields fields)))

(defn drop-all-indexes!
  "Drops all indexes from a collection"
  [coll]
  (.dropIndexes (get-coll coll)))
