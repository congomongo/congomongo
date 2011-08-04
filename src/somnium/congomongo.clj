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
  ^{:author "Andrew Boekhoff",
    :doc "Various wrappers and utilities for the mongodb-java-driver"}
  somnium.congomongo
  (:use     [clojure.walk :only (postwalk)]
            [somnium.congomongo.config :only [*mongo-config*]]
            [somnium.congomongo.coerce :only [coerce coerce-fields coerce-index-fields]])
  (:import  [com.mongodb Mongo DB DBCollection DBObject DBRef ServerAddress WriteConcern Bytes]
            [com.mongodb.gridfs GridFS]
            [com.mongodb.util JSON]
            [org.bson.types ObjectId]))


(defprotocol StringNamed
  (named [s] "convenience for interchangeably handling keywords, symbols, and strings"))
(extend-protocol StringNamed
  clojure.lang.Named
  (named [s] (name s))
  Object
  (named [s] s))

(defn- make-server-address
  "Convenience to make a ServerAddress without reflection warnings."
  [^String host ^Integer port]
  (ServerAddress. host port))

(defn make-connection
  "Connects to one or more mongo instances, returning a connection
that can be used with set-connection! and with-mongo. Each instance is
a map containing values for :host and/or :port."
  ([db]
    (make-connection db {}))
  ([db & instances]
    (let [addresses (->> (if (keyword? (first instances))
                           (list (apply array-map instances)) ; Handle legacy connect args
                           instances)
                      (map (fn [{:keys [host port]}]
                             (make-server-address (or host "127.0.0.1") (or port 27017)))))
          mongo (if (> (count addresses) 1)
                  (Mongo. ^java.util.List addresses)
                  (Mongo. ^ServerAddress (first addresses)))
          n-db (if db (.getDB mongo (named db)) nil)]
      {:mongo mongo :db n-db})))

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
  (.close ^Mongo (:mongo conn)))

(defmacro with-mongo
  "Makes conn the active connection in the enclosing scope.

  When with-mongo and set-connection! interact, last one wins"
  [conn & body]
  `(do
     (let [c# ~conn]
       (assert (connection? c#))
       (binding [*mongo-config* c#]
         ~@body))))

(defn set-connection!
  "Makes the connection active. Takes a connection created by make-connection.

When with-mongo and set-connection! interact, last one wins"
  [connection]
  (alter-var-root #'*mongo-config*
                  (constantly connection)
                  (when (thread-bound? #'*mongo-config*)
                    (set! *mongo-config* connection))))

(defn mongo!
  "Creates a Mongo object and sets the default database.

Does not support replica sets, and will be deprecated in future
releases.  Please use 'make-connection' in combination with
'with-mongo' or 'set-connection!' instead.

   Keyword arguments include:
   :host -> defaults to localhost
   :port -> defaults to 27017
   :db   -> defaults to nil (you'll have to set it anyway, might as well do it now.)"
  {:arglists '([:db ? :host "localhost" :port 27017])}
  [& {:keys [db host port]
      :or {db nil host "localhost" port 27017}}]
  (set-connection! (make-connection db :host host :port port))
  true)

(defn authenticate
  "Authenticate against either the current or a specified database connection.
   Note that authenticating twice against the same database will raise an error."
  ([conn username password]
     (.authenticate ^DB (:db conn)
                    ^String username
                    (.toCharArray ^String password)))
  ([username password]
     (authenticate *mongo-config* username password)))

(def write-concern-map
  {:none com.mongodb.WriteConcern/NONE
   :normal com.mongodb.WriteConcern/NORMAL
   :strict com.mongodb.WriteConcern/SAFE})

(defn set-write-concern
  "Sets the write concern on the connection. Setting is one of :none, :normal, :strict"
  [connection setting]
  (assert (connection? connection))
  (assert (contains? (set (keys write-concern-map)) setting))
  (.setWriteConcern ^Mongo (:db connection)
                    ^com.mongodb.WriteConcern (get write-concern-map setting)))

;; add some convenience fns for manipulating object-ids
(definline object-id ^ObjectId [^String s]
  `(ObjectId. ~s))

;; Make ObjectIds printable under *print-dup*, hiding the
;; implementation-dependent ObjectId class
(defmethod print-dup ObjectId [^ObjectId x ^java.io.Writer w]
  (.write w (str "#=" `(object-id ~(.toString x)))))

;; add convenience get-timestamp method
(defn get-timestamp
  "pulls the timestamp from an ObjectId or a map with a valid
   ObjectId in :_id."
  [obj]
  (let [^ObjectId id (if (instance? ObjectId obj) obj (:_id obj))]
    (when id (.getTime id))))

(definline db-ref
  "Convenience DBRef constructor."
  [ns id]
  `(DBRef. ^DB (:db *mongo-config*)
           ^String (named ~ns)
           ^Object ~id))

(defn db-ref? [x]
  (instance? DBRef x))

(defn collection-exists?
  "Query whether the named collection has been created within the DB."
  [collection]
  (.collectionExists ^DB (:db *mongo-config*)
                     ^String (named collection)))

(defn create-collection!
  "Explicitly create a collection with the given name, which must not already exist.

   Most users will not need this function, and will instead allow
   MongoDB to implicitly create collections when they are written
   to. This function exists primarily to allow the creation of capped
   collections, and so supports the following keyword arguments:

   :capped -> boolean: if the collection is capped
   :size   -> int: collection size (in bytes)
   :max    -> int: max number of documents."
  {:arglists
   '([collection :capped :size :max])}
  ([collection & {:keys [capped size max] :as options}]
     (.createCollection ^DB (:db *mongo-config*)
                        ^String (named collection)
                        (coerce options [:clojure :mongo]))))

(definline ^DBCollection get-coll
  "Returns a DBCollection object"
  [collection]
  `(.getCollection ^DB (:db *mongo-config*)
                   ^String (named ~collection)))

(def query-option-map
  {:tailable    Bytes/QUERYOPTION_TAILABLE
   :slaveok     Bytes/QUERYOPTION_SLAVEOK
   :oplogreplay Bytes/QUERYOPTION_OPLOGREPLAY
   :notimeout   Bytes/QUERYOPTION_NOTIMEOUT
   :awaitdata   Bytes/QUERYOPTION_AWAITDATA})

(defn calculate-query-options
  "Calculates the cursor's query option from a list of options"
   [options]
   (reduce bit-or 0 (map query-option-map (if (keyword? options)
                                            (list options)
                                            options))))

(defn fetch
  "Fetches objects from a collection.
   Note that MongoDB always adds the _id and _ns
   fields to objects returned from the database.
   Optional arguments include
   :where   -> takes a query map
   :only    -> takes an array of keys to retrieve
   :as      -> what to return, defaults to :clojure, can also be :json or :mongo
   :from    -> argument type, same options as above
   :skip    -> number of records to skip
   :limit   -> number of records to return
   :one?    -> defaults to false, use fetch-one as a shortcut
   :count?  -> defaults to false, use fetch-count as a shortcut
   :sort    -> sort the results by a specific key
   :options -> query options [:tailable :slaveok :oplogreplay :notimeout :awaitdata]"
  {:arglists
   '([collection :where :only :limit :skip :as :from :one? :count? :sort :options])}
  [coll & {:keys [where only as from one? count? limit skip sort options]
           :or {where {} only [] as :clojure from :clojure
                one? false count? false limit 0 skip 0 sort nil options []}}]
  (let [n-where (coerce where [from :mongo])
        n-only  (coerce-fields only)
        n-col   (get-coll coll)
        n-limit (if limit (- 0 (Math/abs (long limit))) 0)
        n-sort (when sort (coerce sort [from :mongo]))
        n-options (calculate-query-options options)]
    (cond
      count? (.getCount n-col n-where n-only)
      one?   (if-let [m (.findOne
                         ^DBCollection n-col
                         ^DBObject n-where
                         ^DBObject n-only)]
               (coerce m [:mongo as]) nil)
      :else  (when-let [m (.find ^DBCollection n-col
                                 ^DBObject n-where
                                 ^DBObject n-only
                                 (int skip)
                                 (int n-limit)
                                 (int n-options))]
               (coerce (if n-sort
                         (.sort m n-sort)
                         m) [:mongo as] :many true)))))

(defn fetch-one [col & options]
  (apply fetch col (concat options '[:one? true])))

(defn fetch-count [col & options]
  (apply fetch col (concat options '[:count? true])))

;; add fetch-by-id fn
(defn fetch-by-id [col id & options]
  (apply fetch col (concat options [:one? true :where {:_id id}])))

(defn with-ref-fetching
  "Returns a decorated fetcher fn which eagerly loads db-refs."
  [fetcher]
  (fn [& args]
    (let [as (or (second (drop-while (partial not= :as) args))
                 :clojure)
          f  (fn [[k v]]
               [k (if (db-ref? v)
                    (coerce (.fetch ^DBRef v) [:mongo as])
                    v)])]
      (postwalk (fn [x]
                  (if (map? x)
                    (into {} (map f x))
                    x))
                (apply fetcher args)))))

(defn distinct-values
  "Queries a collection for the distinct values of a given key.
   Returns a vector of the values by default (but see the :as keyword argument).
   The key (a String) can refer to a nested object, using dot notation, e.g., \"foo.bar.baz\".

   Optional arguments include
   :where  -> a query object.  If supplied, distinct values from the result of the query on the collection (rather than from the entire collection) are returned.
   :from   -> specifies what form a supplied :where query is in (:clojure, :json, or :mongo).  Defaults to :clojure.  Has no effect if there is no :where query.
   :as     -> results format (:clojure, :json, or :mongo).  Defaults to :clojure."
  {:arglists
   '([collection key :where :from :as])}
  [coll k & {:keys [where from as]
             :or {where {} from :clojure as :clojure}}]
  (let [query (coerce where [from :mongo])]
    (coerce (.distinct (get-coll coll) k query)
            [:mongo as])))

(defn insert!
  "Inserts a map into collection. Will not overwrite existing maps.
   Takes optional from and to keyword arguments. To insert
   as a side-effect only specify :to as nil."
  {:arglists '([coll obj {:many false :from :clojure :to :clojure}])}
  [coll obj & {:keys [from to many]
               :or {from :clojure to :clojure many false}}]
  (let [coerced-obj (if many
                       (coerce obj [from :mongo] :many many)
                       (coerce obj [from :mongo] :many many))
        ^com.mongodb.WriteConcern normal-concern (get write-concern-map :normal)
        res (if many
              (.insert ^DBCollection (get-coll coll) ^java.util.List coerced-obj)
              (.insert ^DBCollection (get-coll coll) ^DBObject coerced-obj normal-concern))]
    (coerce coerced-obj [:mongo to] :many many)))

(defn mass-insert!
  {:arglists '([coll objs {:from :clojure :to :clojure}])}
  [coll objs & {:keys [from to]
                :or {from :clojure to :clojure}}]
  (insert! coll objs :from from :to to :many true))

;; should this raise an exception if _ns and _id aren't present?
(defn update!
   "Alters/inserts a map in a collection. Overwrites existing objects.
   The shortcut forms need a map with valid :_id and :_ns fields or
   a collection and a map with a valid :_id field."
   {:arglists '([collection old new {:upsert true :multiple false :as :clojure :from :clojure}])}
   [coll old new & {:keys [upsert multiple as from]
                    :or {upsert true multiple false as :clojure from :clojure}}]
   (coerce (.update ^DBCollection  (get-coll coll)
                    ^DBObject (coerce old [from :mongo])
                    ^DBObject (coerce new [from :mongo])
              upsert multiple) [:mongo as]))

(defn fetch-and-modify
  "Finds the first document in the query and updates it.
   Parameters:
       coll         -> the collection
       where        -> query to match
       update       -> update to apply
       :only        -> fields to be returned
       :sort        -> sort to apply before picking first document
       :remove?     -> if true, document found will be removed
       :return-new? -> if true, the updated document is returned,
                       otherwise the old document is returned
                       (or it would be lost forever)
       :upsert?     -> do upsert (insert if document not present)"
  {:arglists '([collection where update {:only nil :sort nil :remove? false
                                         :return-new? false :upsert? false :from :clojure :as :clojure}])}
  [coll where update & {:keys [only sort remove? return-new? upsert? from as]
                        :or {only nil sort nil remove? false
                             return-new? false upsert false from :clojure as :clojure}}]
  (coerce (.findAndModify ^DBCollection (get-coll coll)
                          ^DBObject (coerce where [from :mongo])
                          ^DBObject (coerce only [from :mongo])
                          ^DBObject (coerce sort [from :mongo])
                          remove?
                          ^DBObject (coerce update [from :mongo])
                          return-new? upsert?) [:mongo as]))


(defn destroy!
   "Removes map from collection. Takes a collection name and
    a query map"
   {:arglists '([collection where {:from :clojure}])}
   [c q & {:keys [from]
           :or {from :clojure}}]
   (.remove (get-coll c)
            ^DBObject (coerce q [from :mongo])))

(defn add-index!
  "Adds an index on the collection for the specified fields if it does not exist.  Ordering of fields is
   significant; an index on [:a :b] is not the same as an index on [:b :a].

   By default, all fields are indexed in ascending order.  To index a field in descending order, specify it as
   a vector with a direction signifier (i.e., -1), like so:

   [:a [:b -1] :c]

   This will generate an index on:

      :a ascending, :b descending, :c ascending

   Similarly, [[:a 1] [:b -1] :c] will generate the same index (\"1\" indicates ascending order, the default).

    Options include:
    :name   -> defaults to the system-generated default
    :unique -> defaults to false
    :force  -> defaults to true"
   {:arglists '([collection fields {:name nil :unique false :force true}])}
   [c f & {:keys [name unique force]
           :or {name nil unique false force true}}]
   (-> (get-coll c)
       (.ensureIndex (coerce-index-fields f) ^DBObject (coerce (merge {:force force :unique unique}
                                                                       (if name {:name name}))
                                                                [:clojure :mongo]))))

(defn drop-index!
  "Drops an index on the collection for the specified fields.

  `index` may be a vector representing the key(s) of the index (see somnium.congomongo/add-index! for the
  expected format).  It may also be a String or Keyword, in which case it is taken to be the name of the
  index to be deleted.

  Due to how the underlying MongoDB driver works, if you defined an index with a custom name, you *must*
  delete the index using that name, and not the keys."
  [coll index]
  (if (vector? index)
    (.dropIndex (get-coll coll) (coerce-index-fields index))
    (.dropIndex (get-coll coll) ^String (coerce index [:clojure :mongo]))))

(defn drop-all-indexes!
  "Drops all indexes from a collection"
  [coll]
  (.dropIndexes (get-coll coll)))

(defn get-indexes
  "Get index information on collection"
  {:arglists '([collection :as (:clojure)])}
   [coll & {:keys [as]
            :or {as :clojure}}]
   (map #(into {} %) (.getIndexInfo (get-coll coll))))

(defn command
  "Executes a database command."
  {:arglists '([cmd {:options nil :from :clojure :to :clojure}])}
  [cmd & {:keys [options from to]
          :or {options nil from :clojure to :clojure}}]
  (coerce (if options
            (.command ^DB (:db *mongo-config*)
                      ^DBObject (coerce cmd [from :mongo])
                      (int options))
            (.command ^DB (:db *mongo-config*)
                      ^DBObject (coerce cmd [from :mongo])))
          [:mongo to]))

(defn drop-database!
 "drops a database from the mongo server"
 [title]
 (.dropDatabase ^Mongo (:mongo *mongo-config*) ^String (named title)))

(defn set-database!
  "atomically alters the current database"
  [title]
  (if-let [db (.getDB ^Mongo (:mongo *mongo-config*) ^String (named title))]
    (alter-var-root #'*mongo-config* merge {:db db})
    (throw (RuntimeException. (str "database with title " title " does not exist.")))))

;;;; go ahead and have these return seqs

(defn databases
  "List databases on the mongo server" []
  (seq (.getDatabaseNames ^Mongo (:mongo *mongo-config*))))

(defn collections
  "Returns the set of collections stored in the current database" []
  (seq (.getCollectionNames ^DB (:db *mongo-config*))))

(defn drop-coll!
  [collection]
  "Permanently deletes a collection. Use with care."
  (.drop ^DBCollection (.getCollection ^DB (:db *mongo-config*)
                                       ^String (named collection))))

;;;; GridFS, contributed by Steve Purcell
;;;; question: keep the camelCase keyword for :contentType ?

(definline ^GridFS get-gridfs
  "Returns a GridFS object for the named bucket"
  [bucket]
  `(GridFS. ^DB (:db *mongo-config*) ^String (named ~bucket)))

;; The naming of :contentType is ugly, but consistent with that
;; returned by GridFSFile
(defn insert-file!
  "Insert file data into a GridFS. Data should be either a File,
   InputStream or byte array.
   Options include:
   :filename    -> defaults to nil
   :contentType -> defaults to nil
   :metadata    -> defaults to nil"
  {:arglists '([fs data {:filename nil :contentType nil :metadata nil}])}
  [fs ^bytes data & {:keys [^String filename ^String contentType ^DBObject metadata]
              :or {filename nil contentType nil metadata nil}}]
  (let [^com.mongodb.gridfs.GridFSInputFile f (.createFile (get-gridfs fs) data)]
    (if filename (.setFilename f filename))
    (if contentType (.setContentType f contentType))
    (if metadata (.setMetaData f (coerce metadata [:clojure :mongo])))
    (.save f)
    (coerce f [:mongo :clojure])))

(defn destroy-file!
   "Removes file from gridfs. Takes a GridFS name and
    a query map"
   {:arglists '([fs where {:from :clojure}])}
   [fs q & {:keys [from]
            :or {from :clojure}}]
   (.remove (get-gridfs fs)
            ^DBObject (coerce q [from :mongo])))

(defn fetch-files
  "Fetches objects from a GridFS
   Note that MongoDB always adds the _id and _ns
   fields to objects returned from the database.
   Optional arguments include
   :where  -> takes a query map
   :from   -> argument type, same options as above
   :one?   -> defaults to false, use fetch-one-file as a shortcut"
  {:arglists
   '([fs :where :from :one?])}
  [fs & {:keys [where from one?]
         :or {where {} from :clojure one? false}}]
  (let [n-where (coerce where [from :mongo])
        n-fs   (get-gridfs fs)]
    (if one?
      (if-let [m (.findOne ^GridFS n-fs ^DBObject n-where)]
        (coerce m [:mongo :clojure]) nil)
      (if-let [m (.find ^GridFS n-fs ^DBObject n-where)]
        (coerce m [:mongo :clojure] :many true) nil))))

(defn fetch-one-file [fs & options]
  (apply fetch-files fs (concat options '[:one? true])))

(defn write-file-to
  "Writes the data stored for a file to the supplied output, which
   should be either an OutputStream, File, or the String path for a file."
  [fs file out]
  ;; since .findOne is overloaded and coerce returns different types, we cannot remove the reflection warning:
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne (get-gridfs fs) (coerce file [:clojure :mongo]))]
    ;; since .writeTo is overloaded and we can pass different types, we cannot remove the reflection warning:
    (.writeTo f out)))

(defn stream-from
  "Returns an InputStream from the GridFS file specified"
  [fs file]
  ;; since .findOne is overloaded and coerce returns different types, we cannot remove the reflection warning:
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne (get-gridfs fs) (coerce file [:clojure :mongo]))]
    (.getInputStream f)))

(defn server-eval
  "Sends javascript to the server to be evaluated. js should define a function that takes no arguments. The server will call the function."
  [js & args]
  (let [db ^com.mongodb.DB (:db *mongo-config*)
        m (.doEval db js (into-array Object args))]
    (let [result (coerce m [:mongo :clojure])]
      (if (= 1.0 (:ok result)) ;; In Clojure 1.3.0, 1 != 1.0 so we must compare against 1.0 instead (the JS stuff always returns floats)
        (:retval result)
        (throw (Exception. ^String (format "failure executing javascript: %s" (str result))))))))

(defn map-reduce
  "Performs a map-reduce job on the server.

  Mandatory arguments
  collection -> the collection to run the job on
  mapfn -> a JavaScript map function, as a String.  Should take no arguments.
  reducefn -> a JavaScript reduce function, as a String.  Should take two arguments: a key, and a corresponding array of values
  out -> output descriptor
      With MongoDB 1.8, there are many options:
          a collection name (String or Keyword): output is saved in the named collection, removing any data that previously existed there.
      Or, a configuration map:
          {:replace collection-name}: same as above
          {:merge collection-name}: incorporates results of the MapReduce with any data already in the collection
          {:reduce collection-name}: further reduces with any data already in the collection
          {:inline 1}: creates no collection, and returns the results directly

  See http://www.mongodb.org/display/DOCS/MapReduce for more information, as well as the test code in congomongo_test.clj.

  Optional Arguments
  :out-from    -> indicates what form the out parameter is specified in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :query       -> a query map against collection; if this is specified, the map-reduce job is run on the result of this query instead of on the collection as a whole.
  :query-from  -> if query is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :sort        -> if you want query sorted (for optimization), specify a map of sort clauses here.
  :sort-from   -> if sort is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :limit       -> the number of objects to return from a query collection (defaults to 0; that is, everything).  This pertains to query, NOT the result of the overall map-reduce job!
  :finalize    -> a finalizaton function (JavaScript, as a String).  Should take two arguments: a key and a single value (not an array of values).
  :scope       -> a scope object; variables in the object will be available in the global scope of map, reduce, and finalize functions.
  :scope-from  -> if scope is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :output      -> if you want the resulting documents from the map-reduce job, specify :documents; otherwise, if you want the name of the result collection as a keyword, specify :collection.
                  Defaults to :documents.  If the value of 'out' is {:inline 1}, you will get documents, regardless of what you actually put here.
  :as          -> if :output is set to :documents, determines the form the results take (:clojure, :json, or :mongo) (has no effect if :output is set to :collection; that is always returned as a Clojure keyword).
"
  {:arglists
   '([collection mapfn reducefn out :out-from :query :query-from :sort :sort-from :limit :finalize :scope :scope-from :output :as])}
  [collection mapfn reducefn out & {:keys [out-from query query-from sort sort-from limit finalize scope scope-from output as]
                                    :or {out-from :clojure query nil query-from :clojure sort nil sort-from :clojure
                                         limit nil finalize nil scope nil scope-from :clojure output :documents as :clojure}}]
  (let [;; BasicDBObject requires key-value pairs in the correct order... apparently the first one
        ;; must be :mapreduce
        mr-query (->> [[:mapreduce collection]
                       [:map mapfn]
                       [:reduce reducefn]
                       [:out (coerce out [out-from :mongo])]
                       [:verbose true]
                       (when query [:query (coerce query [query-from :mongo])])
                       (when sort [:sort (coerce sort [sort-from :mongo])])
                       (when limit [:limit limit])
                       (when finalize [:finalize finalize])
                       (when scope [:scope (coerce scope [scope-from :mongo])])]
                      (remove nil?)
                      flatten
                      (apply array-map))
        mr-query (coerce mr-query [:clojure :mongo])
        ^com.mongodb.MapReduceOutput result (.mapReduce (get-coll collection) ^DBObject mr-query)]
    (if (or (= output :documents)
            (= (coerce out [out-from :clojure])
               {:inline 1}))
      (coerce (.results result) [:mongo as] :many true)
      (-> (.getOutputCollection result)
            .getName
            keyword))))
