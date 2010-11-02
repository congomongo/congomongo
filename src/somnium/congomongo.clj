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
            [somnium.congomongo.coerce :only [coerce coerce-fields]])
  (:import  [com.mongodb Mongo DB DBCollection DBObject]
            [com.mongodb.gridfs GridFS]
            [com.mongodb.util JSON]
            [org.bson.types ObjectId]))

(defn make-connection
  "Creates a connection to a mongoDB that can be used with set-connection! and with-mongo"
  [db & {:keys [host port]}] 
  (let [host (or host "localhost")
        port (or port 27017)
        mongo  (Mongo. host port)
        n-db     (if db (.getDB mongo (named db)) nil)]
    {:mongo mongo :db n-db}))

(defn connection? [x]
  (and (map? x)
       (:db x)
       (:mongo x)))

(defn close-connection
  "Closes the connection, and unsets it as the active connection if necessary"
  [conn]
  (assert (connection? conn))
  (if (= conn *mongo-config*)
    (if (thread-bound? #'*mongo-config*)
      (set! *mongo-config* nil)
      (alter-var-root #'*mongo-config* (constantly nil))))
  (.close (:mongo conn)))

(defmacro with-mongo
  "Makes conn the active connection in the enclosing scope.

  When with-mongo and set-connection! interact, last one wins"
  [conn & body]
  `(do
     (assert (connection? ~conn))
     (binding [*mongo-config* ~conn]
       ~@body)))

(defn set-connection!
  "Makes the connection active. Takes a connection created by make-connection.

When with-mongo and set-connection! interact, last one wins"
  [connection]
  (alter-var-root #'*mongo-config*
                  (constantly connection)
                  (when (thread-bound? #'*mongo-config*)
                    (set! *mongo-config* connection))))

(defunk mongo!
  "Creates a Mongo object and sets the default database.
   Keyword arguments include:
   :host -> defaults to localhost
   :port -> defaults to 27017
   :db   -> defaults to nil (you'll have to set it anyway, might as well do it now.)"
  {:arglists '({:db ? :host "localhost" :port 27017})}
  [:db nil :host "localhost" :port 27017]
  (set-connection! (make-connection db :host host :port port))
  true)

(def write-concern-map
     {:none com.mongodb.DB$WriteConcern/NONE
      :normal com.mongodb.DB$WriteConcern/NORMAL
      :strict com.mongodb.DB$WriteConcern/STRICT})

(defn set-write-concern
  "Sets the write concern on the connection. Setting is one of :none, :normal, :strict"
  [connection setting]
  (assert (connection? connection))
  (assert (contains? (set (keys write-concern-map)) setting))
  (.setWriteConcern (:db connection)
                    (get write-concern-map setting)))

;; add some convenience fns for manipulating object-ids
(definline object-id [s]
  `(ObjectId. #^String ~s))

;; Make ObjectIds printable under *print-dup*, hiding the
;; implementation-dependent ObjectId class
(defmethod print-dup ObjectId [x w]
  (.write w (str "#=" `(object-id ~(.toString x)))))

;; add convenience get-timestamp method
(defn get-timestamp
  "pulls the timestamp from an ObjectId or a map with a valid
   ObjectId in :_id."
  [obj]
  (let [id (if (instance? ObjectId obj) obj (:_id obj))]
    (when id (.getTime id))))


(definline get-coll
  "Returns a DBCollection object"
  [collection]
  `(.getCollection #^DB (:db *mongo-config*)
                   #^String (named ~collection)))

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
   :count? -> defaults to false, use fetch-count as a shortcut
   :sort   -> sort the results by a specific key"
  {:arglists
   '([collection :where :only :limit :skip :as :from :one? :count? :sort])}
  [coll :where {} :only [] :as :clojure :from :clojure
   :one? false :count? false :limit 0 :skip 0 :sort nil]
  (let [n-where (coerce where [from :mongo])
        n-only  (coerce-fields only)
        n-col   (get-coll coll)
        n-limit (if limit (- 0 (Math/abs limit)) 0)
        n-sort (when sort (coerce sort [from :mongo]))]
    (cond
      count? (.getCount n-col n-where n-only)
      one?   (if-let [m (.findOne
                         #^DBCollection n-col
                         #^DBObject n-where
                         #^DBObject n-only)]
               (coerce m [:mongo as]) nil)
      :else  (when-let [m (.find #^DBCollection n-col
                               #^DBObject n-where
                               #^DBObject n-only
                               (int skip)
                               (int n-limit))]
               (coerce (if n-sort
                         (.sort m n-sort)
                         m) [:mongo as] :many :true)))))

(defn fetch-one [col & options]
  (apply fetch col (concat options '[:one? true])))

(defn fetch-count [col & options]
  (apply fetch col (concat options '[:count? true])))

;; add fetch-by-id fn
(defn fetch-by-id [col id & options]
  (let [id (if (instance? ObjectId id) id (object-id id))]
      (apply fetch col (concat options [:one? true :where {:_id id}]))))

(defunk distinct-values
  "Queries a collection for the distinct values of a given key.
   Returns a vector of the values by default (but see the :as keyword argument).
   The key (a String) can refer to a nested object, using dot notation, e.g., \"foo.bar.baz\".

   Optional arguments include
   :where  -> a query object.  If supplied, distinct values from the result of the query on the collection (rather than from the entire collection) are returned.
   :from   -> specifies what form a supplied :where query is in (:clojure, :json, or :mongo).  Defaults to :clojure.  Has no effect if there is no :where query.
   :as     -> results format (:clojure, :json, or :mongo).  Defaults to :clojure."
  {:arglists
   '([collection key :where :from :as])}
  [coll k :where {} :from :clojure :as :clojure]
  (let [query (coerce where [from :mongo])]
    (coerce (.distinct (get-coll coll) k query)
            [:mongo as])))

(defunk insert!
  "Inserts a map into collection. Will not overwrite existing maps.
   Takes optional from and to keyword arguments. To insert
   as a side-effect only specify :to as nil."
  {:arglists '([coll obj {:many false :from :clojure :to :clojure}])}
  [coll obj :from :clojure :to :clojure :many false]
  (let [coerced-obj (if many
                       #^java.util.List (coerce obj [from :mongo] :many many)
                       #^DBObject (coerce obj [from :mongo] :many many))
        res (if many
	      (.insert #^DBCollection (get-coll coll) coerced-obj)
	      (.insert #^DBCollection (get-coll coll) coerced-obj (get write-concern-map :normal)))]
    (coerce coerced-obj [:mongo to] :many many)))

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
       (.ensureIndex (coerce-fields f) (coerce {:force force :unique unique} [:clojure :mongo]))))

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
 (.dropDatabase (:mongo *mongo-config*) (named title)))

(defn set-database!
  "atomically alters the current database"
  [title]
  (if-let [db (.getDB (:mongo *mongo-config*) (named title))]
    (alter-var-root #'*mongo-config* merge {:db db})
    (throw (RuntimeException. (str "database with title " title " does not exist.")))))

;;;; go ahead and have these return seqs

(defn databases
  "List databases on the mongo server" []
  (seq (.getDatabaseNames (:mongo *mongo-config*))))

(defn collections
  "Returns the set of collections stored in the current database" []
  (seq (.getCollectionNames #^DB (:db *mongo-config*))))

(defn drop-coll!
  [collection]
  "Permanently deletes a collection. Use with care."
  (.drop #^DBCollection (.getCollection #^DB (:db *mongo-config*)
                                        #^String (named collection))))

;;;; GridFS, contributed by Steve Purcell
;;;; question: keep the camelCase keyword for :contentType ?
 
(definline get-gridfs
  "Returns a GridFS object for the named bucket"
  [bucket]
  `(GridFS. #^DB (:db *mongo-config*) #^String (named ~bucket)))
 
;; The naming of :contentType is ugly, but consistent with that
;; returned by GridFSFile
(defunk insert-file!
  "Insert file data into a GridFS. Data should be either a File,
   InputStream or byte array.
   Options include:
   :filename    -> defaults to nil
   :contentType -> defaults to nil
   :metadata    -> defaults to nil"
  {:arglists '(fs data {:filename nil :contentType nil :metadata nil})}
  [fs data :filename nil :contentType nil :metadata nil]
  (let [f (.createFile (get-gridfs fs) data)]
    (if filename (.setFilename f filename))
    (if contentType (.setContentType f contentType))
    (if metadata (.putAll (.getMetaData f) (coerce metadata [:clojure :mongo])))
    (.save f)
    (coerce f [:mongo :clojure])))
 
(defunk destroy-file!
   "Removes file from gridfs. Takes a GridFS name and
    a query map"
   {:arglists '(fs where {:from :clojure})}
   [fs q :from :clojure]
   (.remove (get-gridfs fs)
            #^DBObject (coerce q [from :mongo])))
 
(defunk fetch-files
  "Fetches objects from a GridFS
   Note that MongoDB always adds the _id and _ns
   fields to objects returned from the database.
   Optional arguments include
   :where  -> takes a query map
   :from   -> argument type, same options as above
   :one?   -> defaults to false, use fetch-one-file as a shortcut"
  {:arglists
   '([fs :where :from :one?])}
  [fs :where {} :from :clojure :one? false]
  (let [n-where (coerce where [from :mongo])
        n-fs   (get-gridfs fs)]
    (if one?
      (if-let [m (.findOne #^GridFS n-fs #^DBObject n-where)]
        (coerce m [:mongo :clojure]) nil)
      (if-let [m (.find #^GridFS n-fs #^DBObject n-where)]
        (coerce m [:mongo :clojure] :many true) nil))))
 
(defn fetch-one-file [fs & options]
  (apply fetch-files fs (concat options '[:one? true])))
 
(defn write-file-to
  "Writes the data stored for a file to the supplied output, which
   should be either an OutputStream, File, or the String path for a file."
  [fs file out]
  (if-let [f (.findOne (get-gridfs fs) (coerce file [:clojure :mongo]))]
    (.writeTo f out)))

(defn stream-from
  "Returns an InputStream from the GridFS file specified"
  [fs file]
  (if-let [f (.findOne (get-gridfs fs) (coerce file [:clojure :mongo]))]
    (.getInputStream f)))

(defn server-eval
  "Sends javascript to the server to be evaluated. js should define a function that takes no arguments. The server will call the function."
  [js & args]
  (let [db #^com.mongodb.DB (:db somnium.congomongo.config/*mongo-config*)
        m (.doEval db js (into-array Object args))]
    (let [result (coerce m [:mongo :clojure])]
      (if (= 1 (:ok result))
        (:retval result)
        (throw (Exception. (format "failure executing javascript: %s" (str result))))))))

(defunk map-reduce
  "Performs a map-reduce job on the server.

  Mandatory arguments
  collection -> the collection to run the job on
  mapfn -> a JavaScript map function, as a String.  Should take no arguments.
  reducefn -> a JavaScript reduce function, as a String.  Should take two arguments: a key, and a corresponding array of values

  See http://www.mongodb.org/display/DOCS/MapReduce for more information, as well as the test code in congomongo_test.clj.

  Optional Arguments
  :query       -> a query map against collection; if this is specified, the map-reduce job is run on the result of this query instead of on the collection as a whole.
  :query-from  -> if query is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :sort        -> if you want query sorted (for optimization), specify a map of sort clauses here.
  :sort-from   -> if sort is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :limit       -> the number of objects to return from a query collection (defaults to 0; that is, everything).  This pertains to query, NOT the result of the overall map-reduce job!
  :out         -> output collection name (String or keyword).  If this is specified, the output collection is made permanent.
  :keeptemp    -> should the output collection be treated as permanent? true or false (defaults to false).
  :finalize    -> a finalizaton function (JavaScript, as a String).  Should take two arguments: a key and a single value (not an array of values).
  :scope       -> a scope object; variables in the object will be available in the global scope of map, reduce, and finalize functions.
  :scope-from  -> if scope is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :output      -> if you want the resulting documents from the map-reduce job, specify :documents; otherwise, if you want the name of the result collection as a keyword, specify :collection.  Defaults to :documents.
  :as          -> if :output is set to :documents, determines the form the results take (:clojure, :json, or :mongo) (has no effect if :output is set to :collection; that is always returned as a Clojure keyword).
"
  {:arglists
   '([collection mapfn reducefn :query :query-from :sort :sort-from :limit :out :keeptemp :finalize :scope :scope-from :output :as])}
  [collection mapfn reducefn
   :query {}
   :query-from :clojure
   :sort {}
   :sort-from :clojure
   :limit 0
   :out nil
   :keeptemp nil
   :finalize ""
   :scope nil
   :scope-from :clojure
   :output :documents
   :as :clojure]
  (let [mr-query {:mapreduce collection
                  :map mapfn
                  :reduce reducefn
                  :query (coerce query [query-from :mongo])
                  :sort (coerce sort [sort-from :mongo])
                  :limit limit
                  :out out
                  :keeptemp keeptemp
                  :finalize finalize
                  :scope (coerce scope [scope-from :mongo])}
        mr-query (coerce mr-query [:clojure :mongo])
        result (.mapReduce (get-coll collection) mr-query)]
    (if (= output :documents)
      (coerce (.results result) [:mongo as] :many :true)
      (-> (.getOutputCollection result)
            .getName
            keyword))))


