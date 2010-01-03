(ns somnium.congomongo.util)

(defmacro defunk 
  "Mostly identitical to defnk in clojure.contrib.def but keeps argmap meta data."
  {:arglists '([title docstring? attr-map? [params*] body])}
  [title & stuff]
  (let [[metad [argvec & body]] (split-with (complement vector?) stuff)
        [args kwargs]           (split-with symbol? argvec)
        syms                    (map #(-> % name symbol) (take-nth 2 kwargs))
        values                  (take-nth 2 (rest kwargs))
        sym-vals                (apply hash-map (interleave syms values))
        default-map             {:keys (vec syms)
                                       :or   sym-vals}]
    `(defn ~title
       ~@metad 
       [~@args & options#]
       (let [~default-map (apply hash-map options#)]
               ~@body))))

(defn named [s]
  "convenience for interchangeably handling keywords, symbols, and strings"
  (if (instance? clojure.lang.Named s) (name s) s))

(defn partition-map
  "creates a hash-map of first and rest pairs from a partitioned collection"
  [coll n]
  (apply merge
         (map #(hash-map (first %) (rest %))
              (partition n coll))))

(defn map-keys
  "applies f to each key in h"
  [f h]
  (zipmap (map f (keys h)) (vals h)))
