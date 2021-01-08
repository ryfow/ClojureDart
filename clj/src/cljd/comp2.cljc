(ns cljd.comp2
  (:refer-clojure :exclude [macroexpand macroexpand-1 munge load-file])
  (:require [clojure.string :as str]))

(defmacro ^:private else->> [& forms]
  `(->> ~@(reverse forms)))

(defn- replace-all [^String s regexp f]
  #?(:cljd
     (.replaceAllMapped s regexp f)
     :clj
     (str/replace s regexp f)))

(def char-map
  {"-"    "_"
   "_"    "_$UNDERSCORE_"
   "$"    "_$DOLLAR_"
   ":"    "_$COLON_"
   "+"    "_$PLUS_"
   ">"    "_$GT_"
   "<"    "_$LT_"
   "="    "_$EQ_"
   "~"    "_$TILDE_"
   "!"    "_$BANG_"
   "@"    "_$CIRCA_"
   "#"    "_$SHARP_"
   "'"    "_$SINGLEQUOTE_"
   "\"" "_$DOUBLEQUOTE_"
   "%"    "_$PERCENT_"
   "^"    "_$CARET_"
   "&"    "_$AMPERSAND_"
   "*"    "_$STAR_"
   "|"    "_$BAR_"
   "{"    "_$LBRACE_"
   "}"    "_$RBRACE_"
   "["    "_$LBRACK_"
   "]"    "_$RBRACK_"
   "/"    "_$SLASH_"
   "\\" "_$BSLASH_"
   "?"    "_$QMARK_"})

(defn munge [s]
  (symbol
   (replace-all (name s) #"[^a-zA-Z0-9]"
                (fn [x]
                  (or (char-map x)
                      (str "_$u"
                           ; TODO :cljd version
                           (str/join "__$u" (map #(Long/toHexString (int %)) x))
                           "_"))))))

(defonce ^:private gens (atom 1))
(defn tmpvar
  ([] (tmpvar ""))
  ([prefix]
   (symbol (str (munge prefix) "_$" (swap! gens inc) "_"))))

(def nses (atom {:current-ns 'user
                 'user {}}))

#?(:clj
   (do
     (defn- roll-leading-opts [body]
       (loop [[k v & more :as body] (seq body) opts {}]
         (if (and body (keyword? k))
           (recur more (assoc opts k v))
           [opts body])))

     (defn- expand-reify [&  opts+specs]
       (let [[opts specs] (roll-leading-opts opts+specs)]
         (list* 'reify* opts
                (map
                 (fn [spec]
                   (if (seq? spec)
                     (let [[mname arglist & body] spec
                           [positional-args [delim & opt-args]] (split-with (complement '#{& |}) arglist)
                           delim (case delim & :named :positional)
                           opt-params
                           (for [[p d] (partition-all 2 1 opt-args)
                                 :when (symbol? p)]
                             [p (when-not (symbol? d) d)])]
                       ;; TODO: mname resolution against protocol ifaces
                       (list* mname positional-args delim opt-params body))
                     spec))
                 specs))))

     (defn- expand-do [& body] (list* 'let* [] body))))

(defn macroexpand-1 [env form]
  (if-let [[f & args] (and (seq? form) (symbol? (first form)) form)]
    (let [name (name f)
          ;; TODO symbol resolution and macro lookup in cljd
          #?@(:clj [clj-var (ns-resolve (find-ns (:current-ns @nses)) f)])]
      ;; TODO add proper expansion here, before defaults
      (cond
        (env f) form
        #?@(:clj ; macro overrides
            [(= 'ns f) form
             (= 'reify f) (apply expand-reify args)
             (= 'do f) (apply expand-do args)])
        (= '. f) form
        #?@(:clj
            [(-> clj-var meta :macro)
             ; force &env to nil when cross-compiling, should be ok
             (apply @clj-var form nil (next form))]
            :cljd
            [TODO TODO])
        (.endsWith name ".")
        (list* 'new
          (symbol (namespace f) (subs name 0 (dec (count name))))
          args)
        (.startsWith name ".")
        (list* '. (first args) (symbol (subs name 1)) (next args))
        :else form))
    form))

(defn macroexpand [env form]
  (let [ex (macroexpand-1 env form)]
    (cond->> ex (not (identical? ex form)) (recur env))))

(declare emit)

(defn atomic?
  [x] (not (coll? x)))

(comment
  ; TODO replace atomic? by something more finegrained
  (defn dart-expr?
    "Takes a dartsexp and returns true if it can be emitted as a Dart expression."
    [x]
    (cond
      (not (coll? x)) true
      (case (when (seq? x) (first x))
        (dart/if dart/let dart/loop dart/let-fn) true false) false
      :else (every? dart-expr? x))))

(defn has-recur?
  "Takes a dartsexp and returns true when it contains an open recur."
  [x]
  (some {'dart/recur true} (tree-seq seq? #(case (first %) (dart/loop dart/fn) nil %) x)))

(defn liftable
  "Takes a dartsexp and returns a [bindings expr] where expr is atomic
   or nil if there are no bindings to lift."
  [x]
  (case (when (seq? x) (first x))
    dart/let
    (if (atomic? (last x))
      (next x)
      (let [tmp (tmpvar)]
        [(concat (second x) [[tmp (last x)]])
         tmp]))
    (dart/if dart/try) ; no ternary for now
    (let [tmp (tmpvar)]
      [[[tmp x]]
       tmp])
    nil))

(defn- lift-arg [must-lift x]
  (or (liftable x)
      (cond
        (atomic? x) [nil x]
        must-lift
        (let [tmp (tmpvar)]
          [[[tmp x]] tmp])
        :else
        [nil x])))

(defn emit-fn-call [fn-call env]
  (let [[positionals [_ & nameds]] (split-with (complement #{'&}) fn-call)
        [bindings fn-call]
        (as-> [nil ()] acc
          (reduce (fn [[bindings fn-call] [k x]]
                  (let [[bindings' x'] (lift-arg (seq bindings) (emit x env))]
                    [(concat bindings' bindings) (list* k x' fn-call)]))
                  acc (reverse (partition 2 nameds)))
          (reduce (fn [[bindings fn-call] x]
                    (let [[bindings' x'] (lift-arg (seq bindings) (emit x env))]
                      [(concat bindings' bindings) (cons x' fn-call)]))
                  acc (reverse positionals)))]
    (cond->> fn-call (seq bindings) (list 'dart/let bindings))))

(defn emit-coll
  ([coll env] (emit-coll identity coll env))
  ([f coll env]
   (let [items (into [] (comp (if (map? coll) cat identity) (map f)) coll)
         [bindings items]
         (reduce (fn [[bindings fn-call] x]
                   (let [[bindings' x'] (lift-arg (seq bindings) (emit x env))]
                     [(concat bindings' bindings) (cons x' fn-call)]))
                 [nil ()] (rseq items))
         fn-sym (cond
                  (map? coll) 'cljd.core/into-map ; is there a cljs equivalent?
                  (vector? coll) 'cljd.core/vec
                  (set? coll) 'cljd.core/set
                  (seq? coll) 'cljd.core/into-list ; should we use apply list?
                  :else (throw (ex-info (str "Can't emit collection " (pr-str coll)) {:form coll})))
         fn-call (list (emit fn-sym env) (vec items))]
     (cond->> fn-call (seq bindings) (list 'dart/let bindings)))))

(defn emit-new [[_ & class+args] env]
  (emit-fn-call class+args env))

(defn emit-dot [[_ obj member & args] env]
  (let [member (name member)
        [_ prop name] (re-matches #"(-)?(.+)" member)
        prop (and prop (nil? args))
        op (if prop 'dart/.- 'dart/.)
        fn-call (emit-fn-call (cons obj args) env)]
    (case (first fn-call)
      dart/let (let [[_ bindings [obj & args]] fn-call]
                 (list 'dart/let bindings (list* op obj name args)))
      (list* op (first fn-call) name (next fn-call)))))

(defn emit-let [[_ bindings & body] env]
  (let [[dart-bindings env]
        (reduce
         (fn [[dart-bindings env] [k v]]
           (let [tmp (tmpvar k)]
             [(conj dart-bindings [tmp (emit v env)])
              (assoc env k tmp)]))
         [[] env] (partition 2 bindings))
        dart-bindings
        (concat dart-bindings (for [x (butlast body)] [nil (emit x env)]))]
    (cond->> (emit (last body) env)
      ; wrap only when ther are actual bindings
      (seq dart-bindings) (list 'dart/let dart-bindings))))

(defn emit-loop [[_ bindings & body] env]
  (let [[dart-bindings env]
        (reduce
         (fn [[dart-bindings env] [k v]]
           (let [tmp (tmpvar k)]
             [(conj dart-bindings [tmp (emit v env)])
              (assoc env k tmp)]))
         [[] env] (partition 2 bindings))]
    (list 'dart/loop dart-bindings (emit (list* 'let* [] body) env))))

(defn emit-recur [[_ & exprs] env]
  (cons 'dart/recur (map #(emit % env) exprs)))

(defn emit-if [[_ test then else] env]
  (let [test (emit test env)]
    (if-some [[bindings test] (liftable test)]
      (list 'dart/let bindings (list 'dart/if test (emit then env) (emit else env)))
      (list 'dart/if test (emit then env) (emit else env)))))

(defn- variadic? [[params]] (some #{'&} params))

(defn- emit-non-variadic-body [[params & body] all-params env]
  (let [env (into env (for [p params] [p (tmpvar p)]))
        body (emit (cons 'do body) env)
        bindings (map vector (map env params) all-params)]
    (cond
      (has-recur? body) (list 'dart/loop bindings body)
      ;; the test below has no functional value,
      ;; it avoids emitting useless dart/lets
      (seq bindings) (list 'dart/let bindings body)
      :else body)))

(defn- emit-variadic-body [[params & body] all-params env]
  #_(list* 'let* (map vector params all-params) body))

(defn emit-fn [[_ & bodies] env]
  (let [name (when (symbol? (first bodies)) (first bodies))
        env (cond-> env name (assoc name (tmpvar name)))
        bodies (cond->> bodies name next)
        bodies (cond-> bodies (vector? (first bodies)) list)
        [variadic & too-many-variadics] (filter variadic? bodies)
        variadic-min
        (some-> variadic first (take-while (complement #{'&})) count)
        non-variadics
        (->> bodies (remove variadic?)
             (group-by (comp count first))
             (sort-by key)
             (into []
                   (map (fn [[n [body & too-many-bodies]]]
                          (when too-many-bodies
                            (throw (ex-info "Can't have 2 overloads with same arity" {})))
                          body))))
        non-variadics-max (-> non-variadics peek first count)
        params-min (or (some-> non-variadics ffirst count) variadic-min)
        params-max (if variadic 20 non-variadics-max)
        all-params (vec (repeatedly params-max tmpvar))
        last-body (or (some-> variadic (emit-variadic-body all-params env))
                      (some-> (peek non-variadics) (emit-non-variadic-body all-params env)))
        non-variadics (cond-> non-variadics (not variadic) pop)
        dart-fn
        (list 'dart/fn (env name) (take params-min all-params) (drop params-min all-params)
              (reduce
               (fn [else [params :as body]]
                 (let [next-p (nth all-params (count params))]
                   (list 'dart/if (list (emit 'cljd/missing-arg? env) next-p)
                         (emit-non-variadic-body body all-params env)
                         else)))
               last-body
               (rseq non-variadics)))]
    (when (some-> variadic-min (< non-variadics-max))
      (throw (ex-info "Can't have fixed arity function with more params than variadic function" {})))
    (if name ; systematically lift named functions
      (list 'dart/let [[nil dart-fn]] (env name))
      dart-fn)))

(defn emit-method [[mname [this-param & fixed-params] opt-kind opt-params & body] env]
  ;; params destructuring will be added by a macro
  ;; opt-params need to have been fully expanded to a list of [symbol default]
  ;; by the macro
  (let [dart-fixed-params (map tmpvar fixed-params)
        dart-opt-params (for [[p d] opt-params]
                          [(case opt-kind
                             :named p ; here p must be a valid dart identifier
                             :positional (tmpvar p))
                           (emit d env)])
        env (into (assoc env this-param 'this)
                  (zipmap (concat fixed-params (map first opt-params))
                          (concat dart-fixed-params (map first dart-opt-params))))
        dart-body (emit (cons 'do body) env)
        recur-params (when (has-recur? dart-body) dart-fixed-params)
        dart-fixed-params (if recur-params
                            (map tmpvar fixed-params)
                            dart-fixed-params)
        dart-body (cond->> dart-body
                    recur-params
                    (list 'dart/loop (map vector recur-params dart-fixed-params)))]
    [mname dart-fixed-params opt-kind dart-opt-params dart-body]))

(defn closed-overs [emitted env]
  (into #{} (keep (set (vals env))) (tree-seq coll? seq emitted)))

(defn method-closed-overs [[mname dart-fixed-params opt-kind dart-opt-params dart-body] env]
  (reduce disj (closed-overs dart-body env) (cons 'this (concat dart-fixed-params (map second dart-opt-params)))))

(declare write-class)

(defn do-def [nses sym m]
  (assoc-in nses [(:current-ns nses) sym] m))

(defn emit-reify [[_ opts & specs] env]
  (let [{:keys [extends] :or {extends 'Object}} opts
        [ctor-op base & ctor-args :as ctor]
        (macroexpand env (cond->> extends (symbol? extends) (list 'new)))
        ctor-meth (when (= '. ctor-op) (first ctor-args))
        ctor-args (cond-> ctor-args (= '. ctor-op) next)
        [positional-ctor-args [_ & named-ctor-args]] (split-with (complement #{'&}) ctor-args)
        positional-ctor-params (repeatedly (count positional-ctor-args) tmpvar)
        named-ctor-params (repeatedly (quot (count named-ctor-args) 2) tmpvar)
        class-name (tmpvar "-reify")  ; TODO change this to a more telling name
        classes (filter #(and (symbol? %) (not= base %)) specs) ; crude
        methods (remove symbol? specs)  ; crude
        mixins(filter (comp :mixin meta) classes)
        ifaces (remove (comp :mixin meta) classes)
        need-nsm (and (seq ifaces) (not-any? (fn [[m]] (case m noSuchMethod true nil)) methods))
        dart-methods (map #(emit-method % env) methods)
        closed-overs (transduce (map #(method-closed-overs % env)) into #{}
                                dart-methods)
        reify-ctor (concat ['new class-name] positional-ctor-args (take-nth 2 (next named-ctor-args)))
        class
        {:name class-name
         :fields closed-overs
         :extends base
         :implements ifaces
         :with mixins
         :ctor-params
         (concat
          (map #(list '. %) closed-overs)
          positional-ctor-params
          named-ctor-params)
         :super-ctor
         {:method ctor-meth ; nil for new
          :args
          (concat positional-ctor-params
                  (interleave (take-nth 2 named-ctor-args) named-ctor-params))}
         :methods dart-methods
         :nsm need-nsm}
        reify-ctor-call (list*
                         'new class-name
                         (concat closed-overs
                                 positional-ctor-args
                                 (take-nth 2 (next named-ctor-args))))]
    (swap! nses do-def class-name
             {:type :class
              :code (with-out-str (write-class class))})
    (emit reify-ctor-call (into env (zipmap closed-overs closed-overs)))))

(declare write-top-dartfn write-top-field)

(defn emit-def [[_ sym expr] env]
  (let [expr (macroexpand env expr)]
    (if (and (seq? expr) (= 'fn* (first expr)) (not (symbol? (second expr))))
      (swap! nses do-def sym
             {:type :dartfn
              :code (with-out-str (write-top-dartfn sym (emit expr env)))})
      (swap! nses do-def sym
             {:type :field
              :code (with-out-str (write-top-field sym (emit (if (seq? expr) (list (list 'fn* [] expr)) expr) env)))}))
    (emit sym env)))

(defn emit-symbol [x env]
  (let [nses @nses
        {:keys [mappings aliases] :as current-ns} (nses (:current-ns nses))]
    (or
     (env x)
     (when (current-ns x) x)
     (get mappings x)
     (when-some [alias (get aliases (namespace x))] (symbol (str alias "." (name x))))
     #_"TODO next form should throw"
     (symbol (str "GLOBAL_" x)))))

(defn emit-quoted [[_ x] env]
  (cond
    (coll? x) (emit-coll #(list 'quote %) x env)
    (symbol? x) (emit (list 'cljd.core/symbol (namespace x) (name x)) env)
    :else (emit x env)))

(defn emit-ns [[_ ns-sym & ns-clauses] _]
  (let [ns-clauses (drop-while #(or (string? %) (map? %)) ns-clauses) ; drop doc and meta for now
        mappings
        (transduce
         (comp
          (mapcat (fn [[directive & args]] (map #(vector directive %) args)))
          (map
           (fn [[directive arg]]
             (case directive
               :require
               (let [arg (if (vector? arg) arg [arg])
                     alias (name (gensym "lib"))
                     clauses (into {} (partition-all 2) (next arg))]
                 (cond-> (assoc {} :imports [[(name (first arg)) alias]])
                   (:as clauses) (assoc-in [:aliases (name (:as clauses))] alias)
                   (:refer clauses) (assoc :mappings (into {} (map #(vector % (str alias "." (name %)))) (:refer clauses)))))
               :import (/ 0)
               :refer-clojure (/ 0)
               :use (/ 0)))))
         (partial merge-with into)
         {} ns-clauses)]
    (swap! nses assoc ns-sym mappings :current-ns ns-sym)))

(defn emit-try [[_ & body] env]
  (let [{body nil catches 'catch [[_ & finally-body]] 'finally}
        (group-by #(when (seq? %) (#{'finally 'catch} (first %))) body)]
    (list 'dart/try
           (emit (cons 'do body) env)
           (for [[_ classname e & [maybe-st & exprs :as body]] catches
                 :let [st (when (and exprs (symbol? maybe-st)) maybe-st)
                       exprs (if st exprs body)
                       env (cond-> (assoc env e (tmpvar e))
                             st (assoc st (tmpvar st)))]]
             [classname (env e) (some-> st env) (emit (cons 'do exprs) env)])
           (some-> finally-body (conj 'do) (emit env)))))

(defn emit-throw [[_ body] env]
  (let [[bindings x] (lift-arg nil (emit body env))]
    (cond->> (list 'dart/throw x) bindings (list 'dart/let bindings))))

(defn emit
  "Takes a clojure form and a lexical environment and returns a dartsexp."
  [x env]
  (let [x (macroexpand env x)]
    (cond
      (symbol? x) (emit-symbol x env)
      #?@(:clj [(char? x) (str x)])
      (or (number? x) (boolean? x) (string? x)) x
      (keyword? x) (recur (list 'cljd.Keyword/intern (namespace x) (name x)) env)
      (nil? x) nil
      (seq? x)
      (let [emit (case (first x)
                   . emit-dot
                   throw emit-throw
                   new emit-new
                   ns emit-ns
                   try emit-try
                   quote emit-quoted
                   let* emit-let
                   loop* emit-loop
                   recur emit-recur
                   if emit-if
                   fn* emit-fn
                   def emit-def
                   reify* emit-reify
                   emit-fn-call)]
        (emit x env))
      (coll? x) (emit-coll x env)
      :else (throw (ex-info (str "Can't compile " (pr-str x)) {:form x})))))

;; WRITING
(defn declaration [locus] (:decl locus ""))
(defn declared [locus]
  ; merge to conserve custom attributes
  (merge  (dissoc locus :fork :decl) (:fork locus)))

(def statement-locus
  {:pre ""
   :post ";\n"})

(def return-locus
  {:pre "return "
   :post ";\n"})

(def expr-locus
  {:pre ""
   :post ""})

(def paren-locus
  {:pre "("
   :post ")"})

(def arg-locus
  {:pre ""
   :post ", "})

(defn declared-var-locus [varname]
  {:pre (str varname "=")
   :post ";\n"})

(defn var-locus [varname]
  {:pre (str "var " varname "=")
   :post ";\n"
   :decl (str "var " varname ";\n")
   :fork (declared-var-locus varname)})

(declare write)

(defn write-top-dartfn [sym x]
  (print (name sym))
  (write x expr-locus)
  (print "\n"))

(defn write-top-field [sym x]
  (write x (var-locus (name sym))))

(defn- write-args [args]
  (let [[positionals nameds] (split-with (complement keyword?) args)]
    (print "(")
    (run! #(write % arg-locus) positionals)
    (run! (fn [[k x]]
            (print (str (name k) ": "))
            (write x arg-locus)) (partition 2 nameds))
    (print ")")))

(defn write-string-literal [s]
  (print
   (str \"
        (replace-all s #"([\x00-\x1f])|[$\"]"
                     (fn [match]
                       (let [[match control-char] (-> match #?@(:cljd [re-groups]))]
                         (if control-char
                           (case control-char
                             "\b" "\\b"
                             "\n" "\\n"
                             "\r" "\\r"
                             "\t" "\\t"
                             "\f" "\\f"
                             "\13" "\\v"
                             (str "\\x"
                                  #?(:clj
                                     (-> control-char (nth 0) long
                                         (+ 0x100)
                                         Long/toHexString
                                         (subs 1))
                                     :cld
                                     (-> control-char
                                         (.codeUnitAt 0)
                                         (.toRadixString 16)
                                         (.padLeft 2 "0")))))
                           (str "\\" match)))))
        \")))

(defn write-literal [x]
  (cond
    (string? x) (write-string-literal x)
    (nil? x) (print 'null)
    :else (pr x)))

(defn write-class [{class-name :name :keys [extends implements with fields ctor-params super-ctor methods nsm]}]
  (print "class" class-name)
  (some->> extends (print " extends"))
  (some->> implements seq (str/join ", ") (print " implements"))
  (some->> with seq (str/join ", ") (print " with"))
  (print " {\n")
  (doseq [field fields] (print (str "final " field ";")))
  (newline)

  (print (str class-name "("))
  (doseq [p ctor-params]
    (print (if (seq? p) (str "this." (second p)) p))
    (print ", "))
  (print "):super")
  (some->> super-ctor :method (str ".") print)
  (write-args (:args super-ctor))
  (print ";\n")

  (doseq [[mname dart-fixed-params opt-kind dart-opt-params dart-body] methods]
    (newline)
    (print mname)
    (print "(")
    (doseq [p dart-fixed-params] (print p) (print ", "))
    (when (seq dart-opt-params)
      (print (case opt-kind :positional "[" "{"))
      (doseq [[p d] dart-opt-params]
        (print p "= ")
        (write d arg-locus))
        (print (case opt-kind :positional "]" "}")))
    (print "){\n")
    (write dart-body return-locus)
    (print "}\n"))

  (when nsm
    (newline)
    (print "noSuchMethod(i)=>super.noSuchMethod(i);\n"))

  (print "}\n"))

(defn write
  "Takes a dartsexp and a locus.
   Prints valid dart code."
  [x locus]
  (cond
    (vector? x)
    (do (print "[") (run! #(write % arg-locus) x) (print "]"))
    (seq? x)
    (case (first x)
      dart/fn
      (let [[_ name fixed-params opt-params body] x]
        (print (:pre locus))
        (some-> name print) ; name is not nil only when locus is statement
        (print "(")
        (run! print (interleave fixed-params (repeat ", ")))
        (when (seq opt-params)
          (print "[")
          (run! print (interleave opt-params (repeat ", ")))
          (print "]"))
        (print "){\n")
        (write body return-locus)
        (print "}")
        (print (:post locus)))
      dart/let
      (let [[_ bindings expr] x]
        (doseq [[v e] bindings]
          (write e (if v (var-locus v) statement-locus)))
        (write expr locus))
      dart/try
      (let [[_ body catches final] x
            decl (declaration locus)
            locus (declared locus)]
        (some-> decl print)
        (print "try {\n")
        (write body locus)
        (print "}\n")
        (doseq [[classname e st expr] catches]
          (print "on ")
          (print classname) ;; TODO aliasing
          (print " catch (")
          (print e)
          (some->> st (print ","))
          (print ") {\n")
          (some-> expr (write locus))
          (print "}\n"))
        (when final
          (print "finally {\n")
          (write final statement-locus)
          (print "}\n")))
      dart/throw
      (let [[_ body] x]
        (print (:pre locus))
        (print "throw ")
        (write body expr-locus)
        (print (:post locus)))
      dart/if
      (let [[_ test then else] x
            decl (declaration locus)
            locus (declared locus)
            test-var (tmpvar "-test")]
        (some-> decl print)
        (write test (var-locus test-var))
        (print (str "if(" test-var "!=null && " test-var "!=false){\n"))
        (write then locus)
        (print "}else{\n")
        (write else locus)
        (print "}\n"))
      dart/loop
      (let [[_ bindings expr] x
            decl (declaration locus)
            locus (-> locus declared (assoc :loop-bindings (map first bindings)))]
        (some-> decl print)
        (doseq [[v e] bindings]
          (write e (var-locus v)))
        (print "do {\n")
        (write expr locus)
        (print "break;\n} while(true);\n"))
      dart/recur
      (let [[_ & exprs] x
            {:keys [loop-bindings]} locus
            expected (count loop-bindings)
            actual (count exprs)]
        (when-not loop-bindings
          (throw (ex-info "Can only recur from tail position." {})))
        (when-not (= expected actual)
          (throw (ex-info (str "Mismatched argument count to recur, expected: "
                               expected " args, got: " actual) {})))
        (let [vars (set loop-bindings)
              vars-usages (->>
                           (map #(into #{} (keep (disj vars %1))
                                       (tree-seq coll? seq %2))
                                loop-bindings exprs)
                           reverse
                           (reductions into)
                           reverse)
              tmps (into {}
                         (map (fn [v vs] (when (vs v) [v (tmpvar v)])) ; TODO using tmpvar in write is going to cause double munging
                              loop-bindings vars-usages))]
          (doseq [[v e] (map vector loop-bindings exprs)]
            (write e (if-some [tmp (tmps v)] (var-locus tmp) (declared-var-locus v))))
          (doseq [[v tmp] tmps]
            (write tmp (declared-var-locus v)))
          (print "continue;\n")))
      dart/.-
      (let [[_ obj fld] x]
        (print (:pre locus))
        (write obj expr-locus)
        (print (str "." fld))
        (print (:post locus)))
      dart/.
      (let [[_ obj meth & args] x]
        (print (:pre locus))
        (case meth
          ;; operators
          "[]" (do
                 (write obj expr-locus)
                 (print "[")
                 (write (first args) expr-locus)
                 (print "]"))
          "[]=" (do
                  (write obj expr-locus)
                  (print "[")
                  (write (first args) expr-locus)
                  (print "]=")
                  (write (second args) expr-locus))
          "~" (do
                (print meth)
                (write obj paren-locus))
          "-" (if args
                (do
                  (write obj paren-locus)
                  (print meth)
                  (write (first args) paren-locus))
                (do
                  (print meth)
                  (write obj paren-locus)))
          ("<" ">" "<=" ">=" "==" "+" "~/" "/" "*" "%" "|" "^" "&" "<<" ">>" ">>>")
          (do
            (write obj paren-locus)
            (print meth)
            (write (first args) paren-locus))
          ;; else plain method
          (do
            (write obj expr-locus)
            (print (str "." meth))
            (write-args args)))
        (print (:post locus)))
      ;; plain fn call
      (let [[f & args] x]
        (print (:pre locus))
        (write f expr-locus)
        (write-args args)
        (print (:post locus))))
    :else (do (print (:pre locus)) (write-literal x) (print (:post locus)))))

;; Compile clj -> dart file
(defn dump-ns [ns-map]
  (doseq [[lib alias] (:imports ns-map)]
    (print "import ")
    (write-string-literal lib)
    (print " as ")
    (print alias)
    (print ";\n"))
  (print "\n")
  (doseq [[sym v] ns-map
          :when (symbol? sym)
          :let [{:keys [type code]} v]]
    (print code)))

(defn load-file [in]
  #?(:clj
     (let [in (clojure.lang.LineNumberingPushbackReader. in)]
       (loop []
         (let [form (read {:eof in} in)]
           (when-not (identical? form in)
             (emit form {})
             (recur)))))))

(defn make-ns-to-out [^String target-dir]
  #?(:clj (let [out-dir (java.io.File. target-dir)]
            (.mkdirs out-dir)
            (fn [ns-sym]
              (let [ns-path (str (.replace (name ns-sym) "." "/") ".dart")
                    ns-file (doto (java.io.File. out-dir ns-path) (-> .getParentFile .mkdirs))
                    writer (java.io.FileWriter. ns-file java.nio.charset.StandardCharsets/UTF_8)]
                (fn
                  ([]
                   (.close writer))
                  ([x]
                   (.write writer (str x)))))))
     :cljd 'TODO))

(defn compile-file [in ns-to-out]
  (load-file in)
  (let [{:keys [current-ns] :as nses} @nses
        out! (ns-to-out current-ns)]
    (out! (with-out-str (dump-ns (nses current-ns))))
    (out!)))

(comment

  (require '[clojure.java.io :as io])
  (binding [*ns* *ns*]
    (ns cljd.bordeaux)
    (ns cljd.ste)
    (ns cljd.user))
  (compile-file (io/reader "test.cljd") (make-ns-to-out "targetdir/ohoh"))

  (set! *warn-on-reflection* true)

  )

(comment
  (emit '(a b c & :d e) {})
  (GLOBAL_a GLOBAL_b GLOBAL_c :d GLOBAL_e)
  (write *1 (var-locus 'RET))

  (emit '(let* [a 1] (println "BOOH") (a 2)) {})
  (dart/let ([_6612 1] [nil (GLOBAL_println "BOOH")]) (_6612 2))
  (write *1 return-locus)

  (emit '(a (b c) (d e)) {})
  (GLOBAL_a (GLOBAL_b GLOBAL_c) (GLOBAL_d GLOBAL_e))
  (write *1 return-locus)

  (emit '(a (side-effect! 42) (let* [d 1] (d e)) (side-effect! 33)) {})
  (dart/let ([_10294 (GLOBAL_side-effect! 42)] [_10292 1] [_10293 (_10292 GLOBAL_e)]) (GLOBAL_a _10294 _10293 (GLOBAL_side-effect! 33)))
  (write *1 return-locus)

  (emit '(a (if b c d)) {})
  (dart/let ([_10299 (dart/if GLOBAL_b GLOBAL_c GLOBAL_d)]) (GLOBAL_a _10299))
  (write *1 return-locus)

  (emit '(if b c d) {})
  (dart/if GLOBAL_b GLOBAL_c GLOBAL_d)
  (write *1 (var-locus 'RET))

  (emit '(if (if true "true") c d) {})
  (dart/let [[_9946 (dart/if true "true" nil)]] (dart/if _9946 GLOBAL_c GLOBAL_d))
  (write *1 (var-locus 'RET))

  (emit '(if b (let* [c 1] c) d) {})
  (dart/if GLOBAL_b (dart/let ([_10417 1]) _10417) GLOBAL_d)
  (write *1 return-locus)

  (emit '(if b (let* [c 1] (if c x y)) d) {})
  (dart/if GLOBAL_b (dart/let ([_10425 1]) (dart/if _10425 GLOBAL_x GLOBAL_y)) GLOBAL_d)
  (write *1 (var-locus 'RET))



  (emit '(if (let* [x 1] x) then else) {})
  (dart/let ([_10434 1]) (dart/if _10434 GLOBAL_then GLOBAL_else))
  (write *1 (var-locus 'RET))

  (emit '(. a "[]" i) {})
  (dart/. GLOBAL_a "[]" GLOBAL_i)
  (write *1 (var-locus 'RET))

  (emit '(let* [b (new List)] (. b "[]=" 0 "hello") b) {})
  (dart/let ([_11752 (GLOBAL_List)] [nil (dart/. _11752 "[]=" 0 "hello")]) _11752)
  (write *1 (var-locus 'RET))

  (emit '(. obj meth) {})
  (dart/. GLOBAL_obj "meth")
  (emit '(. obj -prop) {})
  (dart/.- GLOBAL_obj "prop")

  (emit '(. (let* [o obj] o) -prop) {})
  (write *1 (var-locus 'RET))


  (emit '(. obj meth a & :b :c) {})
  (dart/. GLOBAL_obj "meth" GLOBAL_a :b (GLOBAL_cljd.Keyword/intern nil "c"))

  (emit '(. (. a + b) * (. c + d)) {})
  (dart/. (dart/. GLOBAL_a "+" GLOBAL_b) "*" (dart/. GLOBAL_c "+" GLOBAL_d))
  (write *1 (var-locus 'RET))
  ;; var RET=((GLOBAL_a)+(GLOBAL_b))*((GLOBAL_c)+(GLOBAL_d));

  (emit '(. (. a + (if flag 0 1)) * (. c + d)) {})
  (dart/let ([_12035 (dart/if GLOBAL_flag 0 1)] [_12036 (dart/. GLOBAL_a "+" _12035)]) (dart/. _12036 "*" (dart/. GLOBAL_c "+" GLOBAL_d)))
  (write *1 (var-locus 'RET))
  ;; var _12035;
  ;; var _12039=GLOBAL_flag;
  ;; if(_12039!=null && _12039!=false){
  ;; _12035=0;
  ;; }else{
  ;; _12035=1;
  ;; }
  ;; var _12036=(GLOBAL_a)+(_12035);
  ;; var RET=(_12036)*((GLOBAL_c)+(GLOBAL_d));

  (emit '(. (let* [a (new Obj)] a) meth) {})
  (dart/let ([_11858 (GLOBAL_Obj)]) (dart/. _11858 "meth"))
  (write *1 (var-locus 'RET))
  ;; var _11858=GLOBAL_Obj();
  ;; var RET=_11858.meth();

  (emit '(loop* [a 1] (if test (recur (inc a)) a)) {})
  (dart/loop [[_12413 1]]
    (dart/if GLOBAL_test
      (dart/recur (GLOBAL_inc _12413))
      _12413))
  (write *1 return-locus)
  ;; var _12413=1;
  ;; do {
  ;; var _12416=GLOBAL_test;
  ;; if(_12416!=null && _12416!=false){
  ;; _12413=GLOBAL_inc(_12413, );
  ;; continue;
  ;; }else{
  ;; return _12413;
  ;; }
  ;; break;
  ;; } while(true);

  (emit '(loop* [a 1 b 2] (recur b a)) {})
  (dart/loop [[_12419 1] [_12420 2]] (dart/recur _12420 _12419))
  (write *1 return-locus)
  ;; var _12419=1;
  ;; var _12420=2;
  ;; do {
  ;; var _12423=_12420;
  ;; _12420=_12419;
  ;; _12419=_12423;
  ;; continue;
  ;; break;
  ;; } while(true);

  (emit '(loop* [a 1 b 2] (recur (inc a) (dec b))) {})
  (dart/loop [[_12693 1] [_12694 2]] (dart/recur (GLOBAL_inc _12693) (GLOBAL_dec _12694)))
  (write *1 return-locus)
  ;; var _12426=1;
  ;; var _12427=2;
  ;; do {
  ;; _12426=GLOBAL_inc(_12426, );
  ;; _12427=GLOBAL_dec(_12427, );
  ;; continue;
  ;; break;
  ;; } while(true);


  (emit '(loop* [a 1 b 2] a b 3 4 (recur 1 2 )) {})
  (dart/loop [[_10053 1] [_10054 2]] (dart/let ([nil _10053] [nil _10054] [nil 3] [nil 4]) (dart/recur 1 2)))
  (write *1 return-locus)

  (emit-fn '(fn [x] x) {})
  (dart/fn (_12891) () (dart/let ([_12892 _12891]) _12892))

  (emit-fn '(fn ([x] x) ([x y] y)) {})
  (dart/fn (_12895) (_12896)
    (dart/if (GLOBAL_cljd/missing-arg? _12896)
      (dart/let ([_12897 _12895]) _12897)
      (dart/let ([_12898 _12895] [_12899 _12896]) _12899)))

  (emit-fn '(fn ([x] x) ([x y] y) ([u v w x y] u)) {})
  (dart/fn (_12902) (_12903 _12904 _12905 _12906)
    (dart/if (GLOBAL_cljd/missing-arg? _12903)
      (dart/let ([_12907 _12902]) _12907)
      (dart/if (GLOBAL_cljd/missing-arg? _12904)
        (dart/let ([_12908 _12902] [_12909 _12903]) _12909)
        (dart/let ([_12910 _12902] [_12911 _12903] [_12912 _12904] [_12913 _12905] [_12914 _12906]) _12910))))

  (emit-fn '(fn ([x] (recur x)) ([x y] y) ([u v w x y] u)) {})
  (dart/fn (_13991) (_13992 _13993 _13994 _13995)
    (dart/if (GLOBAL_cljd/missing-arg? _13992)
      (dart/loop ([_14005 _13991]) (dart/recur _14005))
      (dart/if (GLOBAL_cljd/missing-arg? _13993)
        (dart/let ([_14002 _13991] [_14003 _13992]) _14003)
        (dart/let ([_13996 _13991] [_13997 _13992] [_13998 _13993] [_13999 _13994] [_14000 _13995]) _13996))))
  (write *1 (var-locus "XXX"))

  (emit '(let* [inc (fn* [x] (. x "+" 1))] (inc 3)) {})
  (write *1 (var-locus "DDDD"))

  (emit '(loop* [a 4 b 5] a (recur b a)) {})
  (dart/loop [[_8698 4] [_8699 4]] (dart/let ([nil _8698]) _8699))
  (write *1 (var-locus "DDDD"))

  (emit '(do 1 2 3 4 (a 1) "ddd") {})
  (dart/let ([nil 1] [nil 2] [nil 3] [nil 4] [nil (GLOBAL_a 1)]) "ddd")
  (write *1 (var-locus "this"))


  (emit '(or 1 2 3 4 (a 1) "ddd") {})
  (dart/let ([_9757 1]) (dart/if _9757 _9757 (dart/let ([_9758 2]) (dart/if _9758 _9758 (dart/let ([_9759 3]) (dart/if _9759 _9759 (dart/let ([_9760 4]) (dart/if _9760 _9760 (dart/let ([_9761 (GLOBAL_a 1)]) (dart/if _9761 _9761 "ddd"))))))))))
  (write *1 return-locus)

  (macroexpand {} '(fn* nom [a] a))
  )




(comment

  (emit-ns '(ns cljd.user
              (:require [cljd.bordeaux :refer [reviews] :as awesome]
                        [cljd.ste :as ste]
                        ["package:flutter/material.dart"]
                        clojure.string)) {})


  (emit '((((fn* [] (fn* [] (fn* [] 42)))))) {})
  ((((dart/fn () () (dart/let () (dart/fn () () (dart/let () (dart/fn () () (dart/let () 42)))))))))
  (write *1 (var-locus "DDDD"))

  (emit '(fn* [x] x) {})
  (dart/fn nil (_$7_) () (dart/let ([x_$8_ _$7_]) x_$8_))
  (write *1 return-locus)

  (emit '(fn* fname [x] 42) {})
  (dart/let [[nil (dart/fn _16623 (_16624) () (dart/let ([_16625 _16624]) 42))]] _16623)
  (write *1 return-locus)

  (emit '((fn* fname [x] 42)) {})
  (dart/let ([nil (dart/fn _16631 (_16632) () (dart/let ([_16633 _16632]) 42))]) (_16631))
  (write *1 return-locus)

  ()

  (emit '(def oo (fn* [x] 42)) {})
  (write *1 return-locus)



  (emit '(def oo1 42) {})


  (emit '(def oo (fn* [x] (if (.-isOdd x) (recur (. x + 1)) x ))) {})
  nses

  (emit '(def oo "caca\n") {})

  (write *1 return-locus)

  (emit '(fn* aa [x] x) {})
  (dart/let [[nil (dart/fn _16717 (_16718) () (dart/let ([_16719 _16718]) _16719))]] _16717)

  (emit '(fn* [] (fn* aa [x] x)) {})
  (dart/fn nil () () (dart/let [[nil (dart/fn aa_$9_ (_$10_) () (dart/let ([x_$11_ _$10_]) x_$11_))]] aa_$9_))
  (dart/fn nil () () (dart/let ([nil (dart/fn _18396 (_18397) () (dart/let ([_18398 _18397]) (GLOBAL_do _18398)))]) (GLOBAL_do _18396)))

  (emit '(reify Object (boo [self x & y 33] (.toString self))) {})
  (GLOBAL__22982)

  (emit '(reify Object (boo [self x | y 33] (.toString self))) {})
  (GLOBAL__22986)
  (write *1 return-locus)

  (emit '(let [x 42] (reify Object (boo [self] (str x "-" self)))) {})
  (dart/let ([_22991 42]) (GLOBAL__22992 _22991))

  (emit '(let [x 42] (reify Object (boo [self] (let [x 33] (str x "-" self))))) {})
  (dart/let ([x_$4_ 42]) (_reify_$5_))

  (emit '[1 2 3] {})
  (GLOBAL_cljd.core/vec [1 2 3])
  (write *1 expr-locus)

  (emit '[1 (inc 1) [1 1 1]] {})
  (GLOBAL_cljd.core/vec [1 (GLOBAL_inc 1) (GLOBAL_cljd.core/vec [1 1 1])])

  (emit ''[1 (inc 1) [1 1 1]] {})

  (GLOBAL_cljd.core/vec [1 (GLOBAL_inc 1) (GLOBAL_cljd.core/vec [1 1 1])])

  (emit '[1 (inc 1) [(let [x 3] x)]] {})
  (dart/let ([_24320 (GLOBAL_inc 1)] [_24318 3] [_24319 (GLOBAL_cljd.core/vec [_24318])]) (GLOBAL_cljd.core/vec [1 _24320 _24319]))
  (write *1 expr-locus)

  (emit '(let [x (try 1 2 3 4 (catch Exception e e1 (print e) 2 3))] x) {})
  (dart/let ([_17563 (dart/try (dart/let ([nil 1] [nil 2] [nil 3]) 4) (catch Exception [_17564 _17565] (dart/let ([nil (GLOBAL_print _17564)] [nil 2]) 3)))]) _17563)
  (write *1 return-locus)

  (emit '(if (try 1 2 3 4 (catch Exception e "noooo") (finally "log me")) "yeahhh") {})
  (write *1 return-locus)

  (emit '(try (catch E e st)) {})
  (dart/try nil ([E e_$19_ nil GLOBAL_st]) nil)
  (write *1 return-locus)

  (emit '(try 42 33 (catch E e st x) (finally (print "boo"))) {})
  (dart/try (dart/let ([nil 42]) 33) ([E e_$24_ st_$25_ GLOBAL_x]) (GLOBAL_print "boo"))
  (write *1 return-locus)

  (emit '[1 (let [x 2] x) 3] {})
  (dart/let ([__$3_ 2]) (GLOBAL_cljd.core/vec [1 __$3_ 3]))
  (dart/let ([_25768 2]) (GLOBAL_cljd.core/vec [1 _25768 3]))

  (emit '[(f) (let [x 2] x) 3] {})
  (dart/let ([_25772 (GLOBAL_f)] [_25771 2]) (GLOBAL_cljd.core/vec [_25772 _25771 3]))


  (emit '(try 1 2 3 4 (catch Exception e st 1 2)) {})
  (dart/try (dart/let ([nil 1] [nil 2] [nil 3]) 4) (catch Exception [e_$4_ st_$5_] (dart/let ([nil 1]) 2)) (catch Exception [e_$6_] GLOBAL_st))
  (write *1 return-locus)

  (emit '(throw 1) {})
  (dart/throw 1)
  (write *1 (var-locus "prout"))

  (emit '(throw (let [a 1] (. a + 3))) {})
  (dart/let ([a_$28_ 1] [_$29_ (dart/. a_$28_ "+" 3)]) (dart/throw _$29_))
  (write *1 return-locus)




  (emit '(let [a (throw 1)] a) {})
  (dart/let ([a_$9_ (dart/throw 1)]) a_$9_)
  (write *1 return-locus)







  )
