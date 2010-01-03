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

(ns
  #^{:author "Andrew Boekhoff",
     :doc "Various wrappers and utilities for the mongodb-java-driver"}
  somnium.congomongo
  (:use     [somnium.congomongo.config :only [*mongo-config*]]
            [somnium.congomongo.util   :only [named defunk]]
            [somnium.congomongo.coerce :only [coerce coerce-fields]]
            [clojure.contrib.json read write])
  (:import  [com.mongodb Mongo DB DBCollection DBObject]
            [com.mongodb.util JSON]
            [somnium.congomongo ClojureDBObject]))

(defunk mongo!
  "Creates a Mongo object and sets the default database.
   Keyword arguments include:
   :host -> defaults to localhost
   :port -> defaults to 27017
   :db   -> defaults to nil (you'll have to set it anyway, might as well do it now.)"
  {:arglists '({:db ? :host "localhost" :port 27017})}
  [:db nil :host "localhost" :port 27017]
   (let [mongo  (Mongo. host port)
         n-db     (if db (.getDB mongo (named db)) nil)]
     (reset! *mongo-config*
             {:mongo mongo
              :db    n-db})
     true))

;; perhaps split *mongo-config* out into vars for thread-local
;; changes. 

(definline get-coll
  "Returns a DBCollection object"
  [collection]
  `(doto (.getCollection #^DB (:db @*mongo-config*)
                         #^String (named ~collection))
     (.setObjectClass ClojureDBObject)))

(defunk fetch 
  "Fetches objects from a collection.
   Note that MongoDB always adds the _id and _ns
   fields to objects returned from the database.
   Optional arguments include
   :where  -> takes a query map
   :only   -> takes an array of keys to retrieve
   :as     -> what to return, defaults to :clojure, can also be :json or :mongo
   :from   -> argument type, same options as above
   :skip   -> number of records to skip
   :limit  -> number of records to return
   :one?   -> defaults to false, use fetch-one as a shortcut
   :count? -> defaults to false, use fetch-count as a shortcut"
  {:arglists
   '([collection :where :only :limit :skip :as :from :one? :count?])}
  [coll :where {} :only [] :as :clojure :from :clojure
   :one? false :count? false :limit 0 :skip 0]
  (let [n-where (coerce where [from :mongo])
        n-only  (coerce-fields only)
        n-col   (get-coll coll)
        n-limit (if limit (- 0 (Math/abs limit)) 0)]
    (cond
      count? (.getCount n-col n-where n-only)
      one?   (if-let [m (.findOne
                         #^DBCollection n-col
                         #^DBObject n-where
                         #^DBObject n-only)]
               (coerce m [:mongo as]) nil)
      :else  (if-let [m (.find #^DBCollection n-col
                               #^DBObject n-where
                               #^DBObject n-only
                               (int skip)
                               (int n-limit))]
               (coerce m [:mongo as] :many :true) nil))))

(defn fetch-one [col & options]
  (apply fetch col (concat options '[:one? true])))

(defn fetch-count [col & options]
  (apply fetch col (concat options '[:count? true])))

(defunk insert! 
  "Inserts a map into collection. Will not overwrite existing maps.
   Takes optional from and to keyword arguments. To insert
   as a side-effect only specify :to as nil."
  {:arglists '([coll obj {:many false :from :clojure :to :clojure}])}
  [coll obj :from :clojure :to :clojure :many false]
  (let [res (.insert #^DBCollection (get-coll coll)
                     (if many
                       #^java.util.List (coerce obj [from :mongo] :many many)
                       #^DBObject (coerce obj [from :mongo] :many many)))]
      (if to
        (coerce res [:mongo to] :many many))))

(defunk mass-insert!
  {:arglists '([coll objs {:from :clojure :to :clojure}])}
  [coll objs :from :clojure :to :clojure]
  (insert! coll objs :from from :to to :many true))
  
;; should this raise an exception if _ns and _id aren't present?
(defunk update!
   "Alters/inserts a map in a collection. Overwrites existing objects.
   The shortcut forms need a map with valid :_id and :_ns fields or
   a collection and a map with a valid :_id field."
   {:arglists '(collection old new {:upsert true :multiple false :as :clojure :from :clojure})}
   [coll old new :upsert true :multiple false :as :clojure :from :clojure]
   (coerce (.update #^DBCollection  (get-coll coll)
                    #^DBObject (coerce old [from :mongo])
                    #^DBObject (coerce new [from :mongo])
              upsert multiple) [:mongo as]))

(defunk destroy!
   "Removes map from collection. Takes a collection name and
    a query map"
   {:arglists '(collection where {:from :clojure})}
   [c q :from :clojure]
   (.remove (get-coll c)
            #^DBObject (coerce q [from :mongo])))

(defunk add-index!
   "Adds an index on the collection for the specified fields if it does not exist.
    Options include:
    :unique -> defaults to false
    :force  -> defaults to true"
   {:arglists '(collection fields {:unique false :force true})}
   [c f :unique false :force true]
   (-> (get-coll c)
       (.ensureIndex (coerce-fields f) force unique)))

(defn drop-index!
  "Drops an index on the collection for the specified fields"
  [coll fields]
  (.dropIndex (get-coll coll) (coerce-fields fields)))

(defn drop-all-indexes!
  "Drops all indexes from a collection"
  [coll]
  (.dropIndexes (get-coll coll)))

(defunk get-indexes
  "Get index information on collection"
  {:arglists '([collection :as (:clojure)])}
   [coll :as :clojure]
   (map #(into {} %) (.getIndexInfo (get-coll coll))))

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
  "Returns the set of collections stored in the current database" []
  (.getCollectionNames #^DB (:db @*mongo-config*)))

(defn drop-coll!
  [collection]
  "Permanently deletes a collection. Use with care."
  (.drop #^DBCollection (.getCollection #^DB (:db @*mongo-config*)
                                        #^String (named collection))))