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

(ns
  ^{:author "Andrew Boekhoff, Sean Corfield",
    :doc "Various wrappers and utilities for the mongodb-java-driver"}
  somnium.congomongo
  (:require [clojure.string]
            [clojure.walk :refer (postwalk)]
            [somnium.congomongo.config :refer [*mongo-config* *default-query-options*]]
            [somnium.congomongo.coerce :refer [coerce coerce-fields coerce-index-fields]])
  (:import [com.mongodb MongoClient MongoClientOptions MongoClientOptions$Builder
                        MongoClientURI MongoCredential
                        DB DBCollection DBObject DBRef DBCursor CursorType
                        ServerAddress ReadPreference ReadConcern WriteConcern
                        AggregationOptions
                        MapReduceCommand MapReduceCommand$OutputType
                        InsertOptions DBEncoder]
           [com.mongodb.client.model DBCollectionUpdateOptions
                                     DBCollectionCountOptions
                                     DBCollectionFindOptions
                                     DBCollectionRemoveOptions
                                     DBCollectionDistinctOptions
                                     DBCollectionFindAndModifyOptions
                                     Collation]
           [com.mongodb.gridfs GridFS]
           [org.bson.types ObjectId]
           [java.lang.reflect Method Modifier]
           [java.util List]
           [java.util.concurrent TimeUnit]
           [clojure.lang Named Sequential]))

;; TODO: Remove as an unnecessary complexity. Just use `name`.
(defprotocol StringNamed
  (named [s] "convenience for interchangeably handling keywords, symbols, and strings"))
(extend-protocol StringNamed
  Named
  (named [s] (name s))
  Object
  (named [s] s))

(defn- named? [x]
  (instance? Named x))


;; the `make-connection` and helpers

(defn- field->kw
  "Convert camelCase identifier string to hyphen-separated keyword."
  [id]
  (keyword (clojure.string/replace id #"[A-Z]" #(str "-" (Character/toLowerCase ^Character (first %))))))

(def ^:private builder-map
  "A map from keywords to builder invocation functions."
  ;; Be aware that using reflection directly here will also issue Clojure reflection warnings.
  (let [is-builder-method? (fn [^Method f]
                             (let [m (.getModifiers f)]
                               (and (Modifier/isPublic m)
                                    (not (Modifier/isStatic m))
                                    (= MongoClientOptions$Builder (.getReturnType f)))))
        method-name (fn [^Method f] (.getName f))
        builder-call (fn [^MongoClientOptions$Builder m]
                       (eval (list 'fn '[o v]
                                   (list (symbol (str "." m)) 'o 'v))))
        kw-fn-pair (fn [m] [(field->kw m) (builder-call m)])
        method-lookups (->> (.getDeclaredMethods MongoClientOptions$Builder)
                            (filter is-builder-method?)
                            (map method-name)
                            (map kw-fn-pair))]
    (into {} method-lookups)))

(defn mongo-options
  "Returns the `MongoClientOptions`, populated by any specified options,
   e.g. `(mongo-options :auto-connect-retry true)`."
  [& options]
  (let [option-map (apply hash-map options)
        builder-call (fn [b [k v]]
                       (if-let [f (k builder-map)]
                         (f b v)
                         (throw (IllegalArgumentException.
                                  (str k " is not a valid MongoClientOptions$Builder argument")))))]
    (.build ^MongoClientOptions$Builder (reduce builder-call (MongoClientOptions$Builder.) option-map))))

(defn- make-server-address
  "Convenience to make a `ServerAddress` without reflection warnings."
  [^String host ^Integer port]
  (ServerAddress. host port))

(defn- ^MongoClient make-mongo-client
  ([addresses ^MongoCredential credential ^MongoClientOptions options]
   (if (next addresses)
     (MongoClient. ^List addresses credential options)
     (MongoClient. ^ServerAddress (first addresses) credential options)))
  ([addresses ^MongoClientOptions options]
   (if (next addresses)
     (MongoClient. ^List addresses options)
     (MongoClient. ^ServerAddress (first addresses) options))))

(defn- make-connection-args
  "Makes a connection to MongoDB with the passed `db-name` (which may be `nil`) and optional params.

   NOTE: The `username` and `password` must (and `auth-source` may) be supplied for authenticated connections.
         When `auth-source` is omitted, the `db-name` becomes mandatory (for a DB where the user is defined)."
  [^String db-name {:keys [instances instance options ^String username ^String password]
                    {auth-mechanism :mechanism auth-source :source} :auth-source}]
  (when (not= (nil? username) (nil? password))
    (throw (IllegalArgumentException. "Username and password must both be supplied for authenticated connections")))
  (when (and instances instance)
    (throw (IllegalArgumentException. "Only either `instances` or `instance` can be supplied, not both of them")))

  (let [addresses (map (fn [{:keys [host port] :or {host "127.0.0.1" port 27017}}]
                         (make-server-address host port))
                       (or instances
                           (when instance [instance])
                           [{:host "127.0.0.1" :port 27017}]))

        ^MongoClientOptions options (or options (mongo-options))
        ^MongoCredential credential (when (and username password)
                                      (cond
                                        (= :plain auth-mechanism)
                                        (MongoCredential/createPlainCredential username auth-source
                                                                               (.toCharArray password))

                                        (= :scram-1 auth-mechanism)
                                        (MongoCredential/createScramSha1Credential username auth-source
                                                                                   (.toCharArray password))

                                        (= :scram-256 auth-mechanism)
                                        (MongoCredential/createScramSha256Credential username auth-source
                                                                                     (.toCharArray password))

                                        auth-mechanism
                                        (throw (UnsupportedOperationException. (str auth-mechanism " is not supported.")))

                                        :else
                                        (MongoCredential/createCredential username db-name (.toCharArray password))))
        mongo-client (if credential
                       (make-mongo-client addresses credential options)
                       (make-mongo-client addresses options))]
      {:mongo mongo-client
       :db    (when db-name (.getDB mongo-client db-name))}))

(defn- make-connection-uri
  "Makes a connection to MongoDB with the passed URI, authenticating if `username` and `password` are specified."
  [^String uri]
  (let [^MongoClientURI mongo-client-uri (MongoClientURI. uri)
        ^MongoClient mongo-client (MongoClient. mongo-client-uri)
        ^String db-name (.getDatabase mongo-client-uri)]
    {:mongo mongo-client
     :db    (when db-name (.getDB mongo-client db-name))}))

(defn make-connection
  "Connects to one or more MongoDB instances.
   Returns a connection that can be used for `set-connection!` and `with-mongo`.

   This fn accepts the following parameters:

   1. A `db-name` (string, keyword, or symbol) for the database name, and an optional `args` map:
      :instance    -> a MongoDB server instance to connect to
      :instances   -> a list of server instances to connect to (DEPRECATED)
      :options     -> a `MongoClientOptions` object with various settings to control
                      the behavior of a created `MongoClient`
      :username    -> the username to authenticate with
      :password    -> the password to authenticate with
      :auth-source -> the authentication source for authenticated connections in the form
                      of a `{:mechanism <auth-mechanism>, :source <auth-source>}` map,
                      where supported mechanisms = `:plain`, `:scram-1`, `:scram-256`.

      Each `instance` is a map containing values for `:host` and/or `:port`.
      The default values are: for host — `127.0.0.1`, for port — `27017`.
      If no instance is specified, a connection is made to a default one.

      The `username` and `password` must (and `auth-source` may) be supplied for authenticated connections.
      When `auth-source` is omitted, the `db-name` becomes mandatory (for a DB where the user is defined).

   2. A `mongo-client-uri` (string) is also supported.
      It must be prefixed with \"mongodb://\" or \"mongodb+srv://\" to be distinguishable from the DB name.

      When a URI string is passed as the first parameter, the optional `args` are ignored.

      If `username` and `password` are specified in the URI, the connection will be authenticated."
  {:arglists '([db-name {:instance {:host host :port port} :instances instance+ :options mongo-options
                     :username username :password password :auth-source {:mechanism mechanism :source source}}]
               [mongo-client-uri])}
  [db-name-or-mongo-client-uri & {:as args}]
  ;; FIXME: The `named` doesn't always return a String. Drop the whole thing and use `clojure.core/name` here.
  (let [^String str (named db-name-or-mongo-client-uri)]
    (if (or (.startsWith str "mongodb://")
            (.startsWith str "mongodb+srv://"))
      (make-connection-uri str)
      (make-connection-args str args))))


;; connection-related fns

(defn connection?
  "Returns `true` if the argument is a map specifying an active connection.
   Otherwise, returns `false`."
  [x]
  (boolean (and (map? x)
                (contains? x :db)
                (:mongo x))))

(defn ^DB get-db
  "Returns a `DB` object for the passed connection.
   Throws exception if there isn't one."
  [conn]
  (assert (connection? conn))
  (:db conn))

(defn close-connection
  "Closes the `conn` and unsets it as the active connection if necessary."
  [conn]
  (assert (connection? conn))
  (if (= conn *mongo-config*)
    (if (thread-bound? #'*mongo-config*)
      (set! *mongo-config* nil)
      (alter-var-root #'*mongo-config* (constantly nil))))
  (.close ^MongoClient (:mongo conn)))

(defn set-connection!
  "Makes the passed `connection` an active one.
   Takes a connection created by the `make-connection`.

   NOTE: When `with-mongo` and `set-connection!` interact, last one wins."
  [connection]
  (alter-var-root #'*mongo-config*
                  (constantly connection)
                  (when (thread-bound? #'*mongo-config*)
                    (set! *mongo-config* connection))))


;; database-related fns

(defn databases
  "Lists database names on the MongoDB server."
  []
  (seq (.listDatabaseNames ^MongoClient (:mongo *mongo-config*))))

(defn drop-database!
  "Drops a database from the MongoDB server."
  [db-name]
  (when-some [db (.getDB ^MongoClient (:mongo *mongo-config*)
                         ^String (named db-name))]
    (.dropDatabase db)))

(defn set-database!
  "Atomically alters the current database."
  [db-name]
  (if-let [db (.getDB ^MongoClient (:mongo *mongo-config*)
                      ^String (named db-name))]
    (alter-var-root #'*mongo-config* merge {:db db})
    (throw (RuntimeException.
             (str "database with name " db-name " does not exist.")))))


;; handy scoping macros

(defmacro with-mongo
  "Makes the passed `connection` an active one in the enclosing scope.

   NOTE: When `with-mongo` and `set-connection!` interact, last one wins."
  [connection & body]
  `(let [conn# ~connection]
     (assert (connection? conn#))
     (binding [*mongo-config* conn#]
       ~@body)))

(defmacro with-db
  "Makes the `db-name` an active database in the enclosing scope.

   NOTE: When `with-db` and `set-database!` interact, last one wins."
  [db-name & body]
  `(let [^DB db# (.getDB ^MongoClient (:mongo *mongo-config*)
                         (name ~db-name))]
     (binding [*mongo-config* (assoc *mongo-config* :db db#)]
       ~@body)))

;; TODO: Introduce these `*default-query-options*` overrides to other fns.
(defmacro with-default-query-options
  "Sets `options` as the default query options in the enclosing scope.

   These options override a function-specific default parameter values,
   but get overridden themselves by the params passed to a function.

   NOTE: So far, only `fetch` and `fetch-and-modify` fns support these options."
  [options & body]
  `(binding [*default-query-options* ~options]
     ~@body))


;; collections-related fns

(definline ^DBCollection get-coll
  "Returns a DBCollection object"
  [collection]
  `(.getCollection (get-db *mongo-config*)
     ^String (named ~collection)))

(defn collections
  "Returns the set of collections stored in the current database"
  []
  (seq (.getCollectionNames (get-db *mongo-config*))))

(defn drop-coll!
  [collection]
  "Permanently deletes a collection. Use with care."
  (.drop ^DBCollection (.getCollection (get-db *mongo-config*)
                                       ^String (named collection))))

(defn collection-exists?
  "Query whether the named collection has been created within the DB."
  [collection]
  (.collectionExists (get-db *mongo-config*)
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
  {:arglists '([collection {:capped false :size 0 :max 0}])}
  [collection & {:as options}]
  (.createCollection (get-db *mongo-config*)
                     ^String (named collection)
                     (coerce options [:clojure :mongo])))


;; write concerns

(def write-concern-map
  {:acknowledged         WriteConcern/ACKNOWLEDGED
   :fsynced              WriteConcern/JOURNALED
   :journaled            WriteConcern/JOURNALED
   :majority             WriteConcern/MAJORITY
   :replica-acknowledged WriteConcern/W2
   :unacknowledged       WriteConcern/UNACKNOWLEDGED
   ;; these are pre-2.10.x names for write concern:
   :fsync-safe    WriteConcern/JOURNALED
   :journal-safe  WriteConcern/JOURNALED
   :normal        WriteConcern/UNACKNOWLEDGED
   :replicas-safe WriteConcern/W2
   :safe          WriteConcern/ACKNOWLEDGED
   ;; these are left for backward compatibility but are deprecated:
   :replica-safe WriteConcern/W2
   :strict       WriteConcern/ACKNOWLEDGED
   })

(defn set-write-concern
  "Sets the write concern on the connection. Setting is a key in the
  write-concern-map above."
  [connection setting]
  (assert (contains? (set (keys write-concern-map)) setting))
  (.setWriteConcern (get-db connection)
                    ^WriteConcern (get write-concern-map setting)))

(defn set-collection-write-concern!
  "Sets this write concern as default for a collection."
  [collection write-concern]
  (if-let [concern (get write-concern-map write-concern)]
    (.setWriteConcern (get-coll collection) concern)
    (throw (IllegalArgumentException. (str "Unknown write concern " write-concern ".")))))

(defn get-collection-write-concern
  "Gets the currently set write concern for a collection."
  [collection]
    (.getWriteConcern (get-coll collection)))

(defn- illegal-write-concern
  [write-concern]
  (throw (IllegalArgumentException. (str write-concern " is not a valid WriteConcern alias"))))


;; read concerns

(def read-concern-map
  {:default      ReadConcern/DEFAULT
   :local        ReadConcern/LOCAL
   :majority     ReadConcern/MAJORITY
   :linearizable ReadConcern/LINEARIZABLE
   :snapshot     ReadConcern/SNAPSHOT
   :available    ReadConcern/AVAILABLE})

(defn- illegal-read-concern
  [read-concern]
  (throw (IllegalArgumentException. (str read-concern " is not a valid ReadConcern alias"))))


;; fns for manipulating `ObjectId`s

(definline object-id ^ObjectId [^String s]
  `(ObjectId. ~s))

;; Make ObjectIds printable under *print-dup*, hiding the
;; implementation-dependent ObjectId class
(defmethod print-dup ObjectId [^ObjectId x ^java.io.Writer w]
  (.write w (str "#=" `(object-id ~(.toString x)))))

(defn get-timestamp
  "Pulls the timestamp from an `ObjectId` or a map with a valid `ObjectId` in `:_id`."
  [obj]
  (when-let [^ObjectId id (if (instance? ObjectId obj) obj (:_id obj))]
    (.getTime id)))


;; read preferences

(def ^:private read-preference-map
  "Private map of factory functions of ReadPreferences to aliases."
  {:nearest (fn nearest ([] (ReadPreference/nearest)) ([^List tags] (ReadPreference/nearest tags)))
   :primary (fn primary ([] (ReadPreference/primary)) ([_] (throw (IllegalArgumentException. "Read preference :primary does not accept tag sets."))))
   :primary-preferred (fn primary-preferred ([] (ReadPreference/primaryPreferred)) ([^List tags] (ReadPreference/primaryPreferred tags)))
   :secondary (fn secondary ([] (ReadPreference/secondary)) ([^List tags] (ReadPreference/secondary tags)))
   :secondary-preferred (fn secondary-preferred ([] (ReadPreference/secondaryPreferred)) ([^List tags] (ReadPreference/secondaryPreferred tags)))})

(defn ->tagset
  [tag]
  (letfn [(->tag [[k v]]
            (com.mongodb.Tag. (if (named? k) (name k) (str k))
                              (if (named? v) (name v) (str v))))]
    (com.mongodb.TagSet. ^List (map ->tag tag))))

(defn read-preference
  "Creates a ReadPreference from an alias and optional tag sets. Valid aliases are
   :nearest, :primary, :primary-preferred, :secondary, and :secondary-preferred."
  {:arglists '([preference {:first-tag "value", :second-tag "second-value"} {:other-tag-set "other-value"}])}
  [preference & tags]
  (if-let [pref-factory (get read-preference-map preference)]
    (if (empty? tags)
      (pref-factory)
      (pref-factory (map ->tagset tags)))
    (throw (IllegalArgumentException. (str preference " is not a valid ReadPreference alias.")))))

(defn set-read-preference
  "Sets the read preference on the connection (you may supply a ReadPreference or a valid alias)."
  [connection preference]
  (let [p (if (instance? ReadPreference preference)
            preference
            (read-preference preference))]
    (.setReadPreference (get-db connection) ^ReadPreference p)))

(defn set-collection-read-preference!
  "Sets the read preference as default for a collection."
  [collection preference & opts]
  (let [pref (apply read-preference preference opts)]
    (.setReadPreference (get-coll collection) pref)
    pref))

(defn get-collection-read-preference
  "Returns the currently set read preference for a collection"
  [collection]
  (.getReadPreference (get-coll collection)))


;; eager fetching utilities

(defn db-ref
  "Convenience `DBRef` constructor."
  [ns id]
  (DBRef. ^String (named ns)
          ^Object id))

(defn db-ref? [x]
  (instance? DBRef x))

(defn with-ref-fetching
  "Returns a decorated fetcher fn which eagerly loads `db-ref`s."
  [fetcher]
  (fn [& args]
    (let [as (or (second (drop-while (partial not= :as) args))
                 :clojure)
          f  (fn [[k v]]
               [k (if (db-ref? v)
                    (let [v ^DBRef v
                          coll (get-coll (.getCollectionName v))]
                      (coerce (.findOne coll (.getId v)) [:mongo as]))
                    v)])]
      (postwalk (fn [x]
                  (if (map? x)
                    (into {} (map f x))
                    x))
                (apply fetcher args)))))


;; collection operations

(defn set-cursor-options!
  "Sets the options on the cursor"
  [^DBCursor cursor {:keys [tailable secondary-preferred slaveok oplog notimeout awaitdata]}]
  (when tailable
    (.cursorType cursor CursorType/Tailable))
  (when awaitdata
    (.cursorType cursor CursorType/TailableAwait))
  (when (or secondary-preferred slaveok)
    (.setReadPreference cursor (ReadPreference/secondaryPreferred)))
  (when oplog
    (.oplogReplay cursor true))
  (when notimeout
    (.noCursorTimeout cursor true)))
(def set-options! set-cursor-options!) ;; TODO: Backward compatibility. Remove later on?

(defn fetch
  "Fetches objects from a collection.
   Note that MongoDB always adds the `_id` and `_ns` fields to objects returned from the database.

   Required parameters:
   collection         -> the database collection name (string, keyword, or symbol)

   Optional parameters include:
   :one?               -> will result in `findOne` query (defaults to false, use `fetch-one` as a shortcut)
   :count?             -> will result in `getCount` query (defaults to false, use `fetch-count` as a shortcut)
   :as                 -> return value format (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from               -> arguments (`where`, `sort`, `max`, `min`) format (same default/options as for `:as`)
   :where              -> the selection criteria using query operators (a query map)
   :only               -> `projection`; a subset of fields to return for matching documents (an array of keys)
   :sort               -> the sort criteria to apply to the query (`null` means an undefined order of results)
   :skip               -> number of documents to skip
   :limit              -> number of documents to return
   :batch-size         -> number of documents to return per batch (by default, server chooses an appropriate)
   :max-time-ms        -> set the maximum server execution time for this operation (default is `0`, no limit)
   :max-await-time-ms  -> set the maximum amount of time for the server to wait on new documents to satisfy
                          a tailable cursor query (only applies to a `TailableAwait` cursor type)
   :cursor-type        -> set the cursor type (either `NonTailable`, `Tailable`, or `TailableAwait`)
   :no-cursor-timeout? -> prevent server from timing out idle cursors after an inactivity period (10 min)
   :oplog-replay?      -> users should not set this under normal circumstances
   :partial?           -> get partial results from a sharded cluster if one or more shards are unreachable
   :read-preference    -> set the read preference (e.g. :primary or ReadPreference instance)
   :read-concern       -> set the read concern (e.g. :local, see the `read-concern-map` for available options)
   :collation          -> set the collation
   :comment            -> set the comment to the query
   :hint               -> tell the query which index to use (name (string) or [:compound :index] (seq of keys))
   :max                -> set the exclusive upper bound for a specific index
   :min                -> set the minimum inclusive lower bound for a specific index
   :return-key?        -> if true the find operation will return only the index keys in the resulting documents
   :show-record-id?    -> set to true to add a field `$recordId` to the returned documents
   :explain?           -> returns performance information on the query, instead of rows
   :options            -> query options [:tailable :slaveok :oplogreplay :notimeout :awaitdata] (see the NOTE)

   However, not all of the aforementioned optional params affect the `count?` mode, but only these:
   `hint`, `skip`, `limit`, `max-time-ms`, `read-preference`, `read-concern`, and `collation`.

   As well, the `one?` mode doesn't support `options`, `explain?`, `sort`, `limit`, or `hint`.

   NOTE: The `options` use a custom data format, are unnecessary and left for backward compatibility,
         and might be removed in the next major 'congomongo' release."
  {:arglists
   '([collection {:one? false :count? false :as :clojure :from :clojure :where {} :only [] :sort nil :skip 0 :limit 0
             :batch-size nil :max-time-ms nil :max-await-time-ms nil :cursor-type nil :no-cursor-timeout? nil
             :oplog-replay? nil :partial? nil :read-preference nil :read-concern nil :collation nil :comment nil
             :hint nil :max nil :min nil :return-key? nil :show-record-id? nil :explain? false :options []}])}
  [collection & {:as params}]
  (let [{:keys [one? count? as from where only sort skip limit
                batch-size max-time-ms max-await-time-ms cursor-type no-cursor-timeout? oplog-replay? partial?
                read-preference read-preferences read-concern collation comment hint max min return-key? show-record-id?
                explain? options]}
        (merge {:one? false :count? false :as :clojure :from :clojure
                :where {} :only [] :sort nil :skip 0 :limit 0
                :explain? false :options []} ;; specific to `DBCursor`
               *default-query-options*
               params)]
    (when (and one? sort)
      (throw (IllegalArgumentException. "The `fetch-one` (`:one? true`) can't be used with `sort`.
Please, use `fetch` with `:limit 1` instead.")))
    (when (and one? (or (not= [] options) explain? (not= 0 limit) hint))
      (throw (IllegalArgumentException. "The `fetch-one` doesn't support `options`, `explain?`, `limit`, or `hint`")))
    (when-not (or (nil? hint)
                  (string? hint)
                  (and (instance? Sequential hint)
                       (every? #(or (keyword? %)
                                    (and (instance? Sequential %)
                                         (= 2 (count %))
                                         (-> % first keyword?)
                                         (-> % second #{1 -1})))
                               hint)))
      (throw (IllegalArgumentException. ":hint requires a string name of the index, or a seq of keywords that is the index definition")))

    (let [db-coll (get-coll collection)
          query   (coerce where [from :mongo])
          ;; Congomongo originally used do convert passed `limit` into negative number because
          ;; Mongo protocol says:
          ;;  > If the number is negative, then the database will return that number and close
          ;;  > the cursor. No further results for that query can be fetched.
          ;; (see https://docs.mongodb.com/manual/reference/mongodb-wire-protocol/#op-query)
          ;;
          ;; But after bumping mongo-driver from 3.0.2 to 3.2.2 we discovered that if a number
          ;; of available matching documents in Mongo is bigger than `batchSize` (101 by default)
          ;; and `limit` is a negative number then Mongo will return only `batchSize`
          ;; of results and close the cursor. Which is in agreement with Mongo shell docs:
          ;;  > A negative limit is similar to a positive limit but closes the cursor after
          ;;  > returning a single batch of results. As such, with a negative limit, if the
          ;;  > limited result set does not fit into a single batch, the number of documents
          ;;  > received will be less than the specified limit. By passing a negative limit,
          ;;  > the client indicates to the server that it will not ask for a subsequent batch
          ;;  > via getMore.
          ;; (see https://docs.mongodb.com/manual/reference/method/cursor.limit/#negative-values)
          ;;
          ;; Maybe protocol description implies that number can't be bigger than a `batchSize`
          ;; or maybe protocol has been changed and docs aren't updated. Anyway, the current
          ;; behaviour (with negative `limit`) doesn't match expectations, therefore changed
          ;; to keep `limit` as is.
          limit (or limit 0)
          ;; TODO: Backward compatibility issue. Can't drop these `read-preferences` which had
          ;;       this extra 's' in the end. Remove on the next major 'congomongo' release.
          read-preference (or read-preference read-preferences)
          ;; TODO: Requires smth. like `set-collection-read-preference!` to be able to pass tags.
          r-preference (cond
                         (nil? read-preference) nil
                         (instance? ReadPreference read-preference) read-preference
                         :else (somnium.congomongo/read-preference read-preference))
          read-concern (when read-concern
                         (or (read-concern read-concern-map)
                             (illegal-read-concern read-concern)))]
      (if count?
        (.getCount ^DBCollection db-coll
                   ^DBObject query
                   ^DBCollectionCountOptions
                   (let [opts (DBCollectionCountOptions.)]
                     (when hint
                       (if (string? hint)
                         (.hintString opts ^String hint)
                         (.hint opts ^DBObject (coerce-index-fields hint))))
                     (when (int? skip)
                       (.skip opts ^int skip))
                     (when (int? limit)
                       (.limit opts ^int limit))
                     (when (int? max-time-ms)
                       (.maxTime opts ^long max-time-ms TimeUnit/MILLISECONDS))
                     (when r-preference
                       (.readPreference opts ^ReadPreference r-preference))
                     (when read-concern
                       (.readConcern opts ^ReadConcern read-concern))
                     (when (instance? Collation collation)
                       (.collation opts ^Collation collation))
                     opts))

        (let [findOpts (let [opts (DBCollectionFindOptions.)]
                         (when sort
                           (.sort opts ^DBObject (coerce sort [from :mongo])))
                         (when (int? skip)
                           (.skip opts ^int skip))
                         (when (int? limit)
                           (.limit opts ^int limit))
                         (when (int? batch-size)
                           (.batchSize opts ^int batch-size))
                         (when only
                           (.projection opts ^DBObject (coerce-fields only)))
                         (when (int? max-time-ms)
                           (.maxTime opts ^long max-time-ms TimeUnit/MILLISECONDS))
                         (when (int? max-await-time-ms)
                           (.maxAwaitTime opts ^long max-await-time-ms TimeUnit/MILLISECONDS))
                         (when (instance? CursorType cursor-type)
                           (.cursorType opts ^CursorType cursor-type))
                         (when (boolean? no-cursor-timeout?)
                           (.noCursorTimeout opts ^boolean no-cursor-timeout?))
                         (when (boolean? oplog-replay?)
                           (.oplogReplay opts ^boolean oplog-replay?))
                         (when (boolean? partial?)
                           (.showRecordId opts ^boolean partial?))
                         (when r-preference
                           (.readPreference opts ^ReadPreference r-preference))
                         (when read-concern
                           (.readConcern opts ^ReadConcern read-concern))
                         (when (instance? Collation collation)
                           (.collation opts ^Collation collation))
                         (when (string? comment)
                           (.comment opts ^String comment))
                         (when hint
                           (if (string? hint)
                             ;; NOTE: This is currently the one line that prevents
                             ;;       upgrading to the 4.3 driver. String `hint`
                             ;;       doesn't exist in that driver, but it will
                             ;;       be added back in 4.4.
                             ;;       https://jira.mongodb.org/browse/JAVA-4281
                             (.put (.getModifiers opts) "$hint" hint)
                             ;(.hintString opts ^String hint) ;; TODO: Use this instead.
                             (.hint opts ^DBObject (coerce-index-fields hint))))
                         (when max
                           (.max opts ^DBObject (coerce max [from :mongo])))
                         (when min
                           (.min opts ^DBObject (coerce min [from :mongo])))
                         (when (boolean? return-key?)
                           (.returnKey opts ^boolean return-key?))
                         (when (boolean? show-record-id?)
                           (.showRecordId opts ^boolean show-record-id?))
                         opts)]
          (if one?
            (when-some [res (.findOne ^DBCollection db-coll
                                      ^DBObject query
                                      ^DBCollectionFindOptions findOpts)]
              (coerce res [:mongo as]))

            (when-let [cursor (.find ^DBCollection db-coll
                                     ^DBObject query
                                     ^DBCollectionFindOptions findOpts)]
              ;; TODO: Backward compatibility issue. Can't drop these `options`,
              ;;       because they use a custom data format. Remove on the next
              ;;       major 'congomongo' release.
              (when options
                (set-cursor-options! cursor options))
              (if explain?
                (coerce (.explain ^DBCursor cursor) [:mongo as] :many false)
                (coerce cursor [:mongo as] :many true)))))))))

(defn fetch-one [col & options]
  (apply fetch col (concat options '[:one? true])))

(defn fetch-count [col & options]
  (apply fetch col (concat options '[:count? true])))

(defn fetch-by-id [col id & options]
  (apply fetch col (concat options [:one? true :where {:_id id}])))

(defn fetch-by-ids [col ids & options]
  (apply fetch col (concat options [:where {:_id {:$in ids}}])))

(defn distinct-values
  "Finds the distinct values for a specified field across a collection.
   Returns a vector of these values, possibly formatted (according to the `:as` param logic).

   Required parameters:
   collection       -> the database collection
   field-name       -> the field for which to return the distinct values
                       (may be of a nested document; use the \"dot notation\" for this, e.g. \"foo.bar.baz\")

   Optional parameters include:
   :as              -> return value format (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from            -> arguments (`where`) format (same default/options as for `:as`; no effect w/o `:where`)
   :where           -> the selection query to determine the subset of documents for distinct values retrieval
   :read-preference -> set the read preference (e.g. :primary or ReadPreference instance)
   :read-concern    -> set the read concern (e.g. :local, see the `read-concern-map` for available options)
   :collation       -> set the collation"
  {:arglists '([collection field-name {:as :clojure :from :clojure :where nil
                        :read-preference nil :read-concern nil :collation nil}])}
  [collection field-name & {:keys [as from where read-preference read-concern collation]
                            :or {as :clojure from :clojure where nil}}]
  (let [;; TODO: Requires smth. like `set-collection-read-preference!` to be able to pass tags.
        r-preference (cond
                       (nil? read-preference) nil
                       (instance? ReadPreference read-preference) read-preference
                       :else (somnium.congomongo/read-preference read-preference))
        read-concern (when read-concern
                       (or (read-concern read-concern-map)
                           (illegal-read-concern read-concern)))]
    (coerce (.distinct ^DBCollection (get-coll collection)
                       ^String field-name
                       ^DBCollectionDistinctOptions
                       (let [opts (DBCollectionDistinctOptions.)]
                         (when where
                           (.filter opts ^DBObject (coerce where [from :mongo])))
                         (when r-preference
                           (.readPreference opts ^ReadPreference r-preference))
                         (when read-concern
                           (.readConcern opts ^ReadConcern read-concern))
                         (when (instance? Collation collation)
                           (.collation opts ^Collation collation))
                         opts))
            [:mongo as])))

(defn insert!
  "Inserts document(s) into a collection.
   If the collection does not exists on the server, then it will be created.
   If the new document does not contain an '_id' field, it will be added. Will not overwrite existing documents.

   Required parameters:
   collection         -> the database collection
   obj                -> a document or a collection/sequence of documents to insert

   Optional parameters include:
   :many?             -> whether this will insert multiple documents (default is `false`)
   :as                -> return value format (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from              -> arguments (`obj`) format (same default and options as for `:as`)
   :write-concern     -> set the write concern (e.g. :normal, see the `write-concern-map` for available options)
   :continue-on-error -> whether documents will continue to be inserted after a failure to insert one
                         (most commonly due to a duplicate key error; default value is false)
   :bypass-document-validation -> set the bypass document level validation flag
   :encoder           -> set the encoder (of BSONObject to BSON)

   NOTE: To insert as a side-effect only, specify `:as` as `nil`."
  {:arglists '([collection obj {:many? false :as :clojure :from :clojure
                 :write-concern nil :continue-on-error nil :bypass-document-validation nil :encoder nil}])}
  [collection obj & {:keys [many? as from
                            many to ;; TODO: For backward compatibility. Remove later.
                            write-concern continue-on-error bypass-document-validation encoder]
                     :or {many? false as :clojure from :clojure} :as params}]
  (let [coerced-obj (coerce obj [from :mongo] :many (or many many?))
        list-obj (if (or many many?) coerced-obj (list coerced-obj))]
    (.insert ^DBCollection (get-coll collection)
             ^List list-obj
             ^InsertOptions
             (let [opts (InsertOptions.)]
               (when write-concern
                 (if-let [wc (write-concern write-concern-map)]
                   (.writeConcern opts ^WriteConcern wc)
                   (illegal-write-concern write-concern)))
               (when (boolean? continue-on-error)
                 (.continueOnError opts ^boolean continue-on-error))
               (when (boolean? bypass-document-validation)
                 (.bypassDocumentValidation opts ^boolean bypass-document-validation))
               (when (instance? DBEncoder encoder)
                 (.dbEncoder opts ^DBEncoder encoder))
               opts))
    (coerce coerced-obj [:mongo (if (contains? params :to) to as)] :many many)))

(defn mass-insert!
  {:arglists '([coll objs {:from :clojure :to :clojure}])}
  [coll objs & {:keys [from to write-concern]
                :or {from :clojure to :clojure}}]
  (insert! coll objs :from from :to to :many true :write-concern write-concern))

(defn update!
  "Alters/inserts a map in a collection. Overwrites existing objects.
   The shortcut forms need a map with valid `:_id` and `:_ns` fields or
   a collection and a map with a valid `:_id` field.

   Required parameters:
   collection     -> the database collection name (string, keyword, or symbol)
   query          -> the selection criteria for the update (a query map)
   update         -> the modifications to apply (a modifications map)

   Optional parameters include:
   :upsert?       -> do upsert, i.e. insert if document not present (default is `true`)
   :multiple?     -> whether this will update all documents matching the query filter (default is `false`)
   :as            -> return value format (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from          -> arguments (`query`, `update`) format (same default and options as for `:as`)
   :write-concern -> set the write concern (e.g. :normal, see the `write-concern-map` for available options)
   :bypass-document-validation -> set the bypass document level validation flag
   :encoder       -> set the encoder (of BSONObject to BSON)
   :collation     -> set the collation
   :array-filters -> set the array filters option"
  {:arglists '([collection old new {:upsert? true :multiple? false :as :clojure :from :clojure
                     :write-concern nil :bypass-document-validation nil :encoder nil :collation nil
                     :array-filters nil}])}
  [collection query update & {:keys [upsert? multiple? as from write-concern bypass-document-validation
                                     upsert multiple ;; TODO: For backward compatibility. Remove later.
                                     encoder collation array-filters]
                              :or {upsert? true multiple? false as :clojure from :clojure}}]
  (coerce (.update ^DBCollection (get-coll collection)
                   ^DBObject (coerce query [from :mongo])
                   ^DBObject (coerce update [from :mongo])
                   ^DBCollectionUpdateOptions
                   (let [opts (DBCollectionUpdateOptions.)]
                     (.upsert opts ^boolean (or upsert upsert?))
                     (.multi opts ^boolean (or multiple multiple?))
                     (when write-concern
                       (if-let [wc (write-concern write-concern-map)]
                         (.writeConcern opts ^WriteConcern wc)
                         (illegal-write-concern write-concern)))
                     (when (boolean? bypass-document-validation)
                       (.bypassDocumentValidation opts ^boolean bypass-document-validation))
                     (when (instance? DBEncoder encoder)
                       (.encoder opts ^DBEncoder encoder))
                     (when (instance? Collation collation)
                       (.collation opts ^Collation collation))
                     (when array-filters
                       (let [coerced-af (coerce array-filters [:clojure :mongo] :many true)]
                         (.arrayFilters opts ^List coerced-af)))
                     opts))
          [:mongo as]))

(defn fetch-and-modify!
  "Atomically modifies and returns a single document.
   Selects the first document matching the `query` and removes/updates it.
   By default, the returned document does not include the modifications made on the update.

   Required parameters:
   collection          -> the database collection name (string, keyword, or symbol)
   query               -> the selection criteria for the modification (a query map)
   update              -> the modifications to apply to the selected document (a modifications map)

   Optional parameters include:
   :remove?            -> if `true`, removes the selected document
   :return-new?        -> if `true`, returns the modified document rather than the original
   :upsert?            -> if `true`, operation creates a new document if the query returns no documents
   :as                 -> return value format (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from               -> arguments (`query`, `update`, `sort`) format (same default/options as for `:as`)
   :only               -> `projection`; a subset of fields to return for the selected document (an array of keys)
   :sort               -> determines which document will be modified if the query selects multiple documents
   :bypass-document-validation -> set the bypass document level validation flag
   :max-time-ms        -> set the maximum server execution time for this operation (default is `0`, no limit)
   :write-concern      -> set the write concern (e.g. :normal, see the `write-concern-map` for available options)
   :collation          -> set the collation
   :array-filters      -> set the array filters option

   NOTE: An `update` parameter cannot be `nil` unless is passed along with the `:remove? true`."
  {:arglists
   '([collection query update {:as :clojure :from :clojure :remove? false :return-new? false :upsert? false
                          :only nil :sort nil :bypass-document-validation nil :max-time-ms nil :write-concern nil
                          :collation nil :array-filters nil}])}
  [collection query update & {:as params}]
  (let [{:keys [as from remove? return-new? upsert? only sort
                bypass-document-validation max-time-ms write-concern collation array-filters]}
        (merge {:as :clojure :from :clojure :remove? false :return-new? false :upsert? false
                :only nil :sort nil :max-time-ms 0}
               *default-query-options*
               params)]
    (coerce (.findAndModify ^DBCollection (get-coll collection)
                            ^DBObject (coerce query [from :mongo])
                            ^DBCollectionFindAndModifyOptions
                            (let [opts (DBCollectionFindAndModifyOptions.)]
                              (when update
                                (.update opts ^DBObject (coerce update [from :mongo])))
                              (when (boolean? remove?)
                                (.remove opts ^boolean remove?))
                              (when (boolean? return-new?)
                                (.returnNew opts ^boolean return-new?))
                              (when (boolean? upsert?)
                                (.upsert opts ^boolean upsert?))
                              (when only
                                (.projection opts ^DBObject (coerce-fields only)))
                              (when sort
                                (.sort opts ^DBObject (coerce sort [from :mongo])))
                              (when (boolean? bypass-document-validation)
                                (.bypassDocumentValidation opts ^boolean bypass-document-validation))
                              (when (int? max-time-ms)
                                (.maxTime opts ^long max-time-ms TimeUnit/MILLISECONDS))
                              (when write-concern
                                (if-let [wc (write-concern write-concern-map)]
                                  (.writeConcern opts ^WriteConcern wc)
                                  (illegal-write-concern write-concern)))
                              (when (instance? Collation collation)
                                (.collation opts ^Collation collation))
                              (when array-filters
                                (let [coerced-af (coerce array-filters [:clojure :mongo] :many true)]
                                  (.arrayFilters opts ^List coerced-af)))
                              opts))
            [:mongo as])))
(def fetch-and-modify fetch-and-modify!) ;; TODO: Backward compatibility. Remove later on?

(defn destroy!
  "Removes map from a collection.

   Required parameters:
   collection     -> the database collection name (string, keyword, or symbol)
   query          -> the deletion criteria using query operators (a query map),
                     omit or pass an empty `query` to delete all documents in the collection

   Optional parameters include:
   :from          -> arguments (`query`) format (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :write-concern -> set the write concern (e.g. :normal, see the `write-concern-map` for available options)
   :encoder       -> set the encoder (of BSONObject to BSON)
   :collation     -> set the collation"
  {:arglists '([collection query {:from :clojure :write-concern nil :encoder nil :collation nil}])}
  [collection query & {:keys [from write-concern encoder collation]
                       :or {from :clojure}}]
  (.remove ^DBCollection (get-coll collection)
           ^DBObject (coerce query [from :mongo])
           ^DBCollectionRemoveOptions
           (let [opts (DBCollectionRemoveOptions.)]
             (when write-concern
               (if-let [wc (write-concern write-concern-map)]
                 (.writeConcern opts ^WriteConcern wc)
                 (illegal-write-concern write-concern)))
             (when (instance? DBEncoder encoder)
               (.encoder opts ^DBEncoder encoder))
             (when (instance? Collation collation)
               (.collation opts ^Collation collation))
             opts)))

(defn aggregate
  "Executes a pipeline of operations using the Aggregation Framework.
   Returns map {:serverUsed ... :result ... :ok ...}
   :serverUsed - string representing server address
   :result     - the result of the aggregation (if successful)
   :ok         - 1.0 for success
   Requires MongoDB 2.2!"
  {:arglists '([coll op & ops {:from :clojure :to :clojure}])}
  [coll op & ops-and-from-to]
  (let [ops (take-while (complement keyword?) ops-and-from-to)
        from-and-to (drop-while (complement keyword?) ops-and-from-to)
        {:keys [from to] :or {from :clojure to :clojure}} from-and-to
        cursor (.aggregate (get-coll coll)
                           ^List (coerce (conj ops op) [from :mongo])
                           ^AggregationOptions (-> (AggregationOptions/builder)
                                                   (.build)))]
    {:serverUsed (.toString (.getServerAddress cursor))
     :result (coerce cursor [:mongo to] :many true)
     :ok 1.0}))


;; database commands

(defn command!
  "Executes a database command.

   The MongoDB command interface provides access to all non CRUD database operations: authentication;
   user, role and session management; replication and sharding; database administration, diagnostics
   and auditing — are all accomplished with commands. All \"User Commands\" are supported as well.
   See [Database Commands](https://docs.mongodb.com/manual/reference/command/) for the full list.

   Required parameters:
   cmd              -> a string representation of the command to be executed

   Optional parameters include:
   :as              -> return value format (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from            -> arguments (`cmd`) format (same default and options as for `:as`)
   :read-preference -> set the read preference (e.g. :primary or ReadPreference instance);
                       determines where to execute the given command; this will only be applied
                       for a subset of commands (see the `DB#getCommandReadPreference`)
   :encoder         -> set the encoder that knows how to serialise the command"
  {:arglists '([cmd {:as :clojure :from :clojure :read-preference nil :encoder nil}])}
  [cmd & {:keys [as from read-preference encoder
                 to] ;; TODO: For backward compatibility. Remove later.
          :or {as :clojure from :clojure} :as params}]
  (let [db (get-db *mongo-config*)
        r-preference (or read-preference (.getReadPreference db))]
    (coerce (.command db
                      ^DBObject (coerce cmd [from :mongo])
                      ^ReadPreference r-preference
                      ^DBEncoder encoder)
            [:mongo (if (contains? params :to) to as)])))
(def command command!)


;; collection indexes

(defn get-indexes
  "Get indexes information on a collection."
  {:arglists '([collection :as (:clojure)])}
  [coll & {:keys [as]
           :or {as :clojure}}]
  (map #(into {} %) (.getIndexInfo (get-coll coll))))

(defn add-index!
  "Adds an index on the collection for the specified fields if it does not exist.
   Ordering of fields is significant; an index on `[:a :b]` is not the same as an index on `[:b :a]`.

   By default, all fields are indexed in ascending order. To index a field in descending order,
   specify it as a vector with a direction signifier (i.e. `-1`), like so:

   `[:a [:b -1] :c]`

   This will generate an index on:

   `:a ascending, :b descending, :c ascending`

   Similarly, `[[:a 1] [:b -1] :c]` will generate the same index (`1` indicates ascending order,
   the default).

   Optional parameters include:
   :name                      -> defaults to the system-generated default
   :unique                    -> defaults to `false`
   :sparse                    -> defaults to `false`
   :background                -> defaults to `false`
   :partial-filter-expression -> defaults to no filter expression"
  {:arglists '([collection fields {:name nil :unique false :sparse false :partial-filter-expression nil}])}
  [c f & {:keys [name unique sparse background partial-filter-expression]
          :or {name nil unique false sparse false background false}}]
  (-> (get-coll c)
      (.createIndex (coerce-index-fields f)
                    ^DBObject
                    (coerce (merge {:unique unique :sparse sparse :background background}
                                   (if name {:name name})
                                   (if partial-filter-expression
                                     {:partialFilterExpression partial-filter-expression}))
                            [:clojure :mongo]))))

(defn drop-index!
  "Drops an index on the collection for the specified fields.

   The `index` may be a vector representing the key(s) of the index (see `somnium.congomongo/add-index!`
   for the expected format).

   It may also be a string/keyword, in which case it is taken to be the name of the index to be dropped.

   Due to how the underlying MongoDB driver works, if you defined an index with a custom name, you MUST
   delete the index using that name, and not the keys."
  [coll index]
  (if (vector? index)
    (.dropIndex (get-coll coll) (coerce-index-fields index))
    (.dropIndex (get-coll coll) ^String (coerce index [:clojure :mongo]))))

(defn drop-all-indexes!
  "Drops all indexes on the collection."
  [coll]
  (.dropIndexes (get-coll coll)))


;; GridFS (contributed by Steve Purcell)

(definline ^GridFS get-gridfs
  "Returns a GridFS object for the named bucket"
  [bucket]
  `(GridFS. (get-db *mongo-config*) ^String (named ~bucket)))

;; TODO: question: keep the camelCase keyword for :contentType?
;; The naming of :contentType is ugly, but consistent with that
;; returned by GridFSFile
(defn insert-file!
  "Insert file data into a GridFS. Data should be either a File,
   InputStream or byte array.
   Options include:
   :filename    -> defaults to nil
   :contentType -> defaults to nil
   :metadata    -> defaults to nil
   :_id         -> defaults to nil (autogenerate)"
  {:arglists '([fs data {:filename nil :contentType nil :metadata nil}])}
  [fs data & {:keys [^String filename ^String contentType ^DBObject metadata _id]
              :or {filename nil contentType nil metadata nil _id nil}}]
  (let [^com.mongodb.gridfs.GridFSInputFile f (.createFile ^GridFS (get-gridfs fs) data)]
    (if filename (.setFilename f ^String filename))
    (if contentType (.setContentType f contentType))
    (if metadata (.setMetaData f (coerce metadata [:clojure :mongo])))
    (if _id (.setId f _id))
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
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne ^GridFS (get-gridfs fs) ^DBObject (coerce file [:clojure :mongo]))]
    ;; since .writeTo is overloaded and we can pass different types, we cannot remove the reflection warning:
    (.writeTo f out)))

(defn stream-from
  "Returns an InputStream from the GridFS file specified"
  [fs file]
  ;; since .findOne is overloaded and coerce returns different types, we cannot remove the reflection warning:
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne ^GridFS (get-gridfs fs) ^DBObject (coerce file [:clojure :mongo]))]
    (.getInputStream f)))

(defn- mapreduce-type
  [k]
  (get {:replace MapReduceCommand$OutputType/REPLACE
        :merge   MapReduceCommand$OutputType/MERGE
        :reduce  MapReduceCommand$OutputType/REDUCE}
       k
       MapReduceCommand$OutputType/INLINE))

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
  (let [mr-query (coerce (or query {}) [query-from :mongo])
        ;; The output collection and output-type are inherently bound to each
        ;; other. If out is a string/keyword then the output type should be
        ;; INLINE (and the out 'collection' converted to a string)
        ;;
        ;; If out is a map then it should have a single entry, the key is the
        ;; output-type and the value is the output collection
        [out-collection mr-type] (letfn [(convert-map [[k v]]
                                          [(named v) (mapreduce-type k)])]
                                  (cond
                                    (= out {:inline 1}) [nil (mapreduce-type :inline)]
                                    (map? out)          (-> out first convert-map)
                                    (named? out)        [(named out) (mapreduce-type :replace)]))
        ;; Verbose is true by default
        ;; http://api.mongodb.org/java/3.0/com/mongodb/MapReduceCommand.html#setVerbose-java.lang.Boolean-
        mr-command (MapReduceCommand. (get-coll collection)
                                      mapfn
                                      reducefn
                                      (str out-collection)
                                      mr-type
                                      mr-query)]
    (when sort
      (.setSort mr-command (coerce sort [sort-from :mongo])))
    (when limit
      (.setLimit mr-command limit))
    (when finalize
      (.setFinalize mr-command finalize))
    (when scope
      (.setScope mr-command (coerce scope [scope-from :mongo])))

    (let [^com.mongodb.MapReduceOutput result (.mapReduce (get-coll collection) mr-command)]
      (if (or (= output :documents)
              (= (coerce out [out-from :clojure])
                 {:inline 1}))
        (coerce (.results result) [:mongo as] :many true)
        (-> (.getOutputCollection result)
            .getName
            keyword)))))
