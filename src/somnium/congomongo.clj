(ns somnium.congomongo
  (:use     [somnium.congomongo.config
             :only [*mongo-config*]]
            [somnium.congomongo.coerce
             :only [object-to-map map-to-object coerce-many
                    coerce-fields cache-coercions!]]
            [somnium.congomongo.util
             :only [named]])  
  (:import  [com.mongodb Mongo DB DBCollection BasicDBObject]))

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
  [& kwargs]
  (let [argmap (apply hash-map kwargs)
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

(defn fetch [col & kwargs]
  "Fetches objects from a collection. Keyword arguments include
   :where -> takes a query map
   :only  -> takes an array of keys to retrieve
   :as    -> defaults to :clojure, specify :json to get records as json"
  (let [argmap (apply hash-map kwargs)
        where  (map-to-object (or (:where argmap) {}))
        only   (coerce-fields (or (:only argmap) []))
        as     (or (:as argmap) :clojure)]
    (-> (.find #^DBCollection (get-coll col)
               #^BasicDBObject where
               #^BasicDBObject only)
        (coerce-many :db as))))

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
  The shortcut forms need a map with valid :_id and :_ns fields"
  ([map]
     (update! (:_ns map) {:_id (:_id map)} map))
  ([col map]
     (update! col {:_id (:_id map)} map))
  ([col old new]
     (.update #^DBCollection  (get-coll col)
              #^BasicDBObject (map-to-object old)
              #^BasicDBObject (map-to-object new)
              true true)))

