(ns spicerack.core
  (:refer-clojure :exclude [assoc! dissoc!]))

;; TODO
;;
;; reify clojure.lang.IFn so we can support (hm :key)
;;
;; add other data structures
;;
;; * hashSet
;;
;; * treeMap
;;
;; * treeSet
;;
;; * indexTreeList
;;
;; fill out the rest of the params for the builder(s)
;;
;; include in-memory as well as on-disk databases
;;
;; cache eviction for hashmaps
;;
;; modification listeners

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DATABASE

(def database-default-opts
  {:file-mmap-enable? true
   :read-only? false
   :transaction-enable? false
   :checksum-header-bypass? false})

(defn open-database
  "Open a MapDB database backed by `filename`, attempting to create filename's parent directories if needed. See database-default-opts for the defaults."
  [filename & params]
  (assert (or (= nil params) (even? (count params)))
          "The params to open-database must be an even number of key-value pairs.")
  (let [params (merge database-default-opts (apply hash-map params))
        {:keys [file-mmap-enable?
                read-only?
                transaction-enable?
                checksum-header-bypass?]} params]
    ;; don't allow the read-only opening of non-existent files!
    (if (and read-only? (not (.exists (clojure.java.io/as-file filename))))
      (throw (AssertionError. (str "File `" filename "` opened read-only, but does not exist!"))))
    ;; ensure parent directories exist
    (.mkdirs (java.io.File. (.getParent (java.io.File. filename))))
    (.make
     (cond-> (org.mapdb.DBMaker/fileDB (clojure.java.io/file filename))
       file-mmap-enable?       (.fileMmapEnableIfSupported)
       read-only?              (.readOnly)
       transaction-enable?     (.transactionEnable)
       checksum-header-bypass? (.checksumHeaderBypass)))))

(defn commit
  "Commit pending changes to this database."
  [db]
  (.commit db))

(defn rollback
  "Rollback pending changes to this database."
  [db]
  (.rollback db))

(defn close
  "Close this `db`."
  [db]
  (.close db))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DATA STRUCTURES

(def serializers
  "Lookup table of serializer types for MapDB. These are used as optional hints for the database to improve performance while serializing objects."
  {:big-decimal        org.mapdb.Serializer/BIG_DECIMAL
   :big-integer        org.mapdb.Serializer/BIG_INTEGER
   :boolean            org.mapdb.Serializer/BOOLEAN
   :byte               org.mapdb.Serializer/BYTE
   :byte-array         org.mapdb.Serializer/BYTE_ARRAY
   :byte-array-delta   org.mapdb.Serializer/BYTE_ARRAY_DELTA
   :byte-array-nosize  org.mapdb.Serializer/BYTE_ARRAY_NOSIZE
   :char               org.mapdb.Serializer/CHAR
   :char-array         org.mapdb.Serializer/CHAR_ARRAY
   :class              org.mapdb.Serializer/CLASS
   :date               org.mapdb.Serializer/DATE
   :double             org.mapdb.Serializer/DOUBLE
   :double-array       org.mapdb.Serializer/DOUBLE_ARRAY
   :elsa               org.mapdb.Serializer/ELSA
   :float              org.mapdb.Serializer/FLOAT
   :float-array        org.mapdb.Serializer/FLOAT_ARRAY
   :illegal-access     org.mapdb.Serializer/ILLEGAL_ACCESS
   :integer            org.mapdb.Serializer/INTEGER
   :integer-delta      org.mapdb.Serializer/INTEGER_DELTA
   :integer-packed     org.mapdb.Serializer/INTEGER_PACKED
   :int-array          org.mapdb.Serializer/INT_ARRAY
   :java               org.mapdb.Serializer/JAVA ; NB java object serialization, the default
   :long               org.mapdb.Serializer/LONG
   :long-array         org.mapdb.Serializer/LONG_ARRAY
   :long-delta         org.mapdb.Serializer/LONG_DELTA
   :long-packed        org.mapdb.Serializer/LONG_PACKED
   :recid              org.mapdb.Serializer/RECID
   :recid-array        org.mapdb.Serializer/RECID_ARRAY
   :short              org.mapdb.Serializer/SHORT
   :short-array        org.mapdb.Serializer/SHORT_ARRAY
   :string             org.mapdb.Serializer/STRING
   :string-ascii       org.mapdb.Serializer/STRING_ASCII
   :string-delta       org.mapdb.Serializer/STRING_DELTA
   :string-intern      org.mapdb.Serializer/STRING_INTERN
   :string-nosize      org.mapdb.Serializer/STRING_NOSIZE
   :string-orighash    org.mapdb.Serializer/STRING_ORIGHASH
   :uuid               org.mapdb.Serializer/UUID})

(defn open-hashmap
  "Create or re-open a MapDB hashmap named `hashmap-name` in `db`. Params are: :key-serializer and :value-serializer to select serializers for keys and/or values (this is done for performance)."
  [db hashmap-name & params]
  (assert (or (= nil params) (even? (count params)))
          "The params to open-hashmap must be an even number of key-value pairs.")
  (let [params (merge {:key-serializer :java
                       :value-serializer :java}
                      (apply hash-map params))
        hmap   (.hashMap db
                         hashmap-name
                         (serializers (:key-serializer params))
                         (serializers (:value-serializer params)))]
    (.createOrOpen hmap)))

(defn assoc!
  "Write the key/value pair (`k`,`v`) to hashmap `m`. Returns v."
  [m k v]
  (.put m k v)
  v)

(def put!
  "Deprecated! Use assoc! instead. This is an alias for backwards compatibility."
  assoc!)

(defn dissoc!
  "Remove key `k` from hashmap `m`. Returns the removed value."
  [m k]
  (.remove m k))

(def remove!
  "Deprecated! Use dissoc! instead. This is an alias for backwards compatibility."
  dissoc!)

(defn update!
  "Calls function `f` with the current value associated with `k` in hashmap `m` as the parameter, then writes the result back to m. If k has not yet been set, it's previous value will be nil. Returns the value written."
  [m k f & args]
  (let [new-value (apply f (.get m k) args)]
    (.put m k new-value)
    new-value))

(defn get-db
  "Get this `data-structure` instance's underlying database. Useful if one wants to call commit in a context where the data-structure is in scope but DB is not."
  [data-structure]
  (.getDB data-structure))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; leftovers

;; HANDY, THIS:
;;
;; (mapcat
;;  #(vector (keyword (.replace (clojure.string/lower-case (:name %)) "_" "-"))
;;           (symbol (str (:declaring-class %) "/" (:name %))))
;;  (sort-by :name
;;           (filter #(re-find #"^[A-Z_]*$" (str (:name %)))
;;                   (:members (clojure.reflect/reflect org.mapdb.Serializer)))))
