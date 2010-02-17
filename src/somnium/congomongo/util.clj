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


(defmacro opt-fn
  "experimental helper for creating
   overloaded fns that accept option maps
   with a helper macro that acts like a
   function that accepts keyword args"
  [m-name default-map pos-args & body]
  (let [f-name (symbol (str "*" m-name))
        skeys (map symbol (map name (keys default-map)))
        dmap  (zipmap skeys (vals default-map))
        dest  {:keys (vec skeys) :or dmap}
        avec  (vec (cons dest pos-args))]
    `(do
       ;; main-fn
       (defn ~f-name
         ~avec
         ~@body)
       (defmacro ~m-name
         [& args#]
         (let [[*args# opts#] (split-with (complement keyword?) args#)
               *opts#         (apply hash-map opts#)]
           (apply list (quote ~f-name) *opts# *args#)))
       (alter-meta! (resolve (quote ~m-name))
                    merge
                    {:arglists (quote [~'& ~'args ~default-map])}))))
