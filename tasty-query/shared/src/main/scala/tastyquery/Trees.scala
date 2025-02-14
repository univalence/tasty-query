package tastyquery

import tastyquery.Constants.*
import tastyquery.Contexts.*
import tastyquery.Exceptions.*
import tastyquery.Names.*
import tastyquery.Spans.*
import tastyquery.Symbols.*
import tastyquery.Types.*
import tastyquery.TypeTrees.*

object Trees {

  /** The method part of an application node, possibly enclosed in a block
    *  with only valdefs as statements. the reason for also considering blocks
    *  is that named arguments can transform a call into a block, e.g.
    *   <init>(b = foo, a = bar)
    * is transformed to
    *   { val x$1 = foo
    *     val x$2 = bar
    *     <init>(x$2, x$1)
    *   }
    */
  private def methPart(tree: Tree): Tree = stripApply(tree) match {
    case TypeApply(fn, _) => methPart(fn)
    // case AppliedTypeTree(fn, _) => methPart(fn) // !!! should not be needed
    case Block(stats, expr) => methPart(expr)
    case mp                 => mp
  }

  /** If this is an application, its function part, stripping all
    *  Apply nodes (but leaving TypeApply nodes in). Otherwise the tree itself.
    */
  private def stripApply(tree: Tree): Tree = tree match {
    case Apply(fn, _) => stripApply(fn)
    case _            => tree
  }

  abstract class Tree(val span: Span) {
    private var myType: Type | Null = null

    /** Compute the actual term of this tree. */
    protected def calculateType(using Context): Type

    /** The term type of this tree.
      *
      * If this tree is not a term (e.g., it is a definition), its `tpe` is
      * `NoType`.
      */
    final def tpe(using Context): Type = {
      val local = myType
      if local != null then local
      else
        val computed = calculateType
        myType = computed
        computed
    }

    def withSpan(span: Span): Tree

    protected def subtrees: List[Tree] = this match {
      case PackageDef(pid, stats)                   => stats
      case ImportSelector(imported, renamed, bound) => imported :: renamed :: Nil
      case Import(expr, selectors)                  => expr :: selectors
      case Export(expr, selectors)                  => expr :: selectors
      case ClassDef(name, rhs, symbol)              => rhs :: Nil
      case Template(constr, parents, self, body) =>
        (constr :: parents.collect { case p if p.isInstanceOf[Tree] => p.asInstanceOf[Tree] }) ++ (self :: body)
      case ValDef(name, tpt, rhs, symbol)         => rhs :: Nil
      case DefDef(name, params, tpt, rhs, symbol) => params.flatMap(_.merge) :+ rhs
      case Select(qualifier, name)                => qualifier :: Nil
      case Super(qual, mix)                       => qual :: Nil
      case Apply(fun, args)                       => fun :: args
      case TypeApply(fun, args)                   => fun :: Nil
      case Typed(expr, tpt)                       => expr :: Nil
      case Assign(lhs, rhs)                       => lhs :: rhs :: Nil
      case NamedArg(name, arg)                    => arg :: Nil
      case Block(stats, expr)                     => stats :+ expr
      case If(cond, thenPart, elsePart)           => cond :: thenPart :: elsePart :: Nil
      case Lambda(meth, tpt)                      => meth :: Nil
      case Match(selector, cases)                 => selector :: cases
      case CaseDef(pattern, guard, body)          => pattern :: guard :: body :: Nil
      case Bind(name, body, symbol)               => body :: Nil
      case Alternative(trees)                     => trees
      case Unapply(fun, implicits, patterns)      => fun :: implicits ++ patterns
      case SeqLiteral(elems, elemtpt)             => elems
      case While(cond, body)                      => cond :: body :: Nil
      case Throw(expr)                            => expr :: Nil
      case Try(expr, cases, finalizer)            => (expr :: cases) :+ finalizer
      case Return(expr, from)                     => expr :: from :: Nil
      case Inlined(expr, caller, bindings)        => expr :: bindings

      case _: TypeMember | _: TypeParam | _: Ident | _: ReferencedPackage | _: This | _: New | _: Literal | _: SelfDef |
          EmptyTree =>
        Nil
    }

    protected def typeTrees: List[TypeTree] = this match {
      case ImportSelector(imported, renamed, bound) => bound :: Nil
      case TypeMember(_, rhs, _) =>
        if (rhs.isInstanceOf[TypeTree]) rhs.asInstanceOf[TypeTree] :: Nil else Nil
      case Template(constr, parents, self, body) =>
        parents.collect { case p if p.isInstanceOf[TypeTree] => p.asInstanceOf[TypeTree] }
      case ValDef(name, tpt, rhs, symbol)         => tpt :: Nil
      case DefDef(name, params, tpt, rhs, symbol) => tpt :: Nil
      case TypeApply(fun, args)                   => args
      case New(tpt)                               => tpt :: Nil
      case Typed(expr, tpt)                       => tpt :: Nil
      case Lambda(meth, tpt)                      => tpt :: Nil
      case SeqLiteral(elems, elemtpt)             => elemtpt :: Nil

      // no type tree inside
      case _ => Nil
    }

    def walkTree[R](op: Tree => R)(reduce: (R, R) => R, default: => R): R = {
      // Apply the operation to the tree itself and all its sutbrees. Reduce the result with the given @reduce function
      def rec(t: Tree): R = reduce(op(t), t.subtrees.map(rec).foldLeft(default)(reduce))
      rec(this)
    }

    /* If the operation does not produce a result, simply apply it to all subtrees of the tree */
    def walkTree(op: Tree => Unit): Unit = walkTree[Unit](op)((_, _) => (), ())

    def walkTypeTrees[R](op: TypeTree => R)(reduce: (R, R) => R, default: => R): R =
      // Apply the operation to all type trees of the current tree and all type trees of all subtrees
      walkTree(_.typeTrees.foldLeft(default)((curRes, tpt) => reduce(curRes, op(tpt))))(reduce, default)

    def walkTypeTrees(op: TypeTree => Unit): Unit = walkTypeTrees[Unit](op)((_, _) => (), ())
  }

  trait DefTree(val symbol: Symbol)

  case class PackageDef(pid: PackageSymbol, stats: List[Tree])(span: Span) extends Tree(span) with DefTree(pid) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): PackageDef = PackageDef(pid, stats)(span)
  }

  case class ImportSelector(imported: Ident, renamed: Tree = EmptyTree, bound: TypeTree = EmptyTypeTree)(span: Span)
      extends Tree(span) {

    /** It's a `given` selector */
    val isGiven: Boolean = imported.name.isEmpty

    /** It's a `given` or `_` selector */
    val isWildcard: Boolean = isGiven || imported.name == nme.Wildcard

    /** The imported name, EmptyTermName if it's a given selector */
    val name: TermName = imported.name.asInstanceOf[TermName]

    /** The renamed part (which might be `_`), if present, or `name`, if missing */
    val rename: TermName = renamed match {
      case Ident(rename: TermName) => rename
      case _                       => name
    }

    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): ImportSelector = ImportSelector(imported, renamed, bound)(span)
  }

  case class Import(expr: Tree, selectors: List[ImportSelector])(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): Import = Import(expr, selectors)(span)
  }

  /** import expr.selectors */
  case class Export(expr: Tree, selectors: List[ImportSelector])(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): Export = Export(expr, selectors)(span)
  }

  /** mods class name template     or
    *  mods trait name template     or
    *  mods type name = rhs   or
    *  mods type name >: lo <: hi,          if rhs = TypeBoundsTree(lo, hi)      or
    *  mods type name >: lo <: hi = rhs     if rhs = TypeBoundsTree(lo, hi, alias) and opaque in mods
    */
  abstract class TypeDef(name: TypeName, override val symbol: TypeSymbol)(span: Span)
      extends Tree(span)
      with DefTree(symbol) {
    protected final def calculateType(using Context): Type = NoType
  }

  case class ClassDef(name: TypeName, rhs: Template, override val symbol: ClassSymbol)(span: Span)
      extends TypeDef(name, symbol)(span) {
    override final def withSpan(span: Span): ClassDef = ClassDef(name, rhs, symbol)(span)
  }

  /** A type member has a type tree rhs if the member is defined by the user, or typebounds if it's synthetic */
  case class TypeMember(name: TypeName, rhs: TypeTree | TypeBounds, override val symbol: TypeMemberSymbol)(span: Span)
      extends TypeDef(name, symbol)(span) {
    override final def withSpan(span: Span): TypeMember = TypeMember(name, rhs, symbol)(span)
  }

  /** The bounds are a type tree if the method is defined by the user and bounds-only if it's synthetic */
  case class TypeParam(
    name: TypeName,
    bounds: TypeBoundsTree | TypeBounds | TypeLambdaTree,
    override val symbol: TypeParamSymbol
  )(span: Span)
      extends TypeDef(name, symbol)(span) {
    override final def withSpan(span: Span): TypeParam = TypeParam(name, bounds, symbol)(span)
  }

  /** `constr extends parents { self => body }`
    *
    * holder for details of a Class definition
    *
    * @param classParent -- the parent whose constructor is called.
    *                       If the template defines a class, this is its only class parent.
    * @param parents        trait parents of the template and the class parent if the template defines a trait.
    */
  case class Template(constr: DefDef, parents: List[Apply | Block | TypeTree], self: SelfDef, body: List[Tree])(
    span: Span
  ) extends Tree(span) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): Template = Template(constr, parents, self, body)(span)
  }

  /** mods val name: tpt = rhs */
  case class ValDef(name: TermName, tpt: TypeTree, rhs: Tree, override val symbol: TermSymbol)(span: Span)
      extends Tree(span)
      with DefTree(symbol) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): ValDef = ValDef(name, tpt, rhs, symbol)(span)
  }

  /** Self type definition `name: tpt =>`. */
  case class SelfDef(name: TermName, tpt: TypeTree)(span: Span) extends Tree(span):
    protected def calculateType(using Context): Type = NoType

    override def withSpan(span: Span): SelfDef = SelfDef(name, tpt)(span)
  end SelfDef

  object SelfDef:
    private[tastyquery] val ReusableEmpty: SelfDef =
      SelfDef(nme.Wildcard, EmptyTypeTree)(NoSpan)
  end SelfDef

  type ParamsClause = Either[List[ValDef], List[TypeParam]]

  /** mods def name[tparams](vparams_1)...(vparams_n): tpt = rhs */
  case class DefDef(
    name: TermName,
    paramLists: List[ParamsClause],
    resultTpt: TypeTree,
    rhs: Tree,
    override val symbol: TermSymbol
  )(span: Span)
      extends Tree(span)
      with DefTree(symbol) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): DefDef = DefDef(name, paramLists, resultTpt, rhs, symbol)(span)
  }

  /** name */
  abstract case class Ident(name: TermName)(span: Span) extends Tree(span)

  /** A free identifier, that has no defining symbol.
    *
    * This seems to always be a wildcard.
    */
  final class FreeIdent(name: TermName, tpe: Type)(span: Span) extends Ident(name)(span) {
    protected final def calculateType(using Context): Type = tpe

    override final def withSpan(span: Span): FreeIdent = FreeIdent(name, tpe)(span)
  }

  /** An identifier appearing in an `import` clause; it has no type. */
  final class ImportIdent(name: TermName)(span: Span) extends Ident(name)(span) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): ImportIdent = ImportIdent(name)(span)
  }

  abstract class SimpleRef(name: TermName, tpe: Type)(span: Span) extends Ident(name)(span) {
    protected final def calculateType(using Context): Type = tpe
  }

  final class TermRefTree(name: TermName, tpe: Type)(span: Span) extends SimpleRef(name, tpe)(span) {
    override final def withSpan(span: Span): TermRefTree = TermRefTree(name, tpe)(span)
  }

  /** reference to a package, seen as a term */
  final class ReferencedPackage(val fullyQualifiedName: FullyQualifiedName)(span: Span)
      extends Ident(fullyQualifiedName.sourceName.asSimpleName)(span) {
    protected final def calculateType(using Context): Type =
      PackageRef(fullyQualifiedName)

    override final def withSpan(span: Span): ReferencedPackage = ReferencedPackage(fullyQualifiedName)(span)

    override def toString: String = s"ReferencedPackage($fullyQualifiedName)"
  }

  object ReferencedPackage {
    def unapply(r: ReferencedPackage): Option[TermName] = Some(r.name)
  }

  /** qualifier.termName */
  case class Select(qualifier: Tree, name: TermName)(span: Span) extends Tree(span) {
    // not final because it is overridden in SelectIn
    protected def calculateType(using Context): Type =
      NamedType.possibleSelFromPackage(qualifier.tpe, name)

    override def withSpan(span: Span): Select = Select(qualifier, name)(span)
  }

  class SelectIn(qualifier: Tree, name: SignedName, selectOwner: TypeRef)(span: Span)
      extends Select(qualifier, name)(span) {

    override protected final def calculateType(using Context): Type =
      TermRef(qualifier.tpe, LookupIn(selectOwner, name))

    override final def withSpan(span: Span): SelectIn = SelectIn(qualifier, name, selectOwner)(span)

    override def toString: String = s"SelectIn($qualifier, $name, $selectOwner)"
  }

  /** `qual.this` */
  // TODO: to assign the type if qualifier is empty, traverse the outer contexts to find the first enclosing class
  case class This(qualifier: Option[TypeIdent])(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      qualifier.fold(NoType)(q =>
        q.toType match
          case pkg: PackageRef => pkg
          case tref: TypeRef   => ThisType(tref)
      )

    override final def withSpan(span: Span): This = This(qualifier)(span)
  }

  /** C.super[mix], where qual = C.this */
  case class Super(qual: Tree, mix: Option[TypeIdent])(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      qual.tpe

    override final def withSpan(span: Span): Super = Super(qual, mix)(span)
  }

  /** fun(args) */
  case class Apply(fun: Tree, args: List[Tree])(span: Span) extends Tree(span):

    private def resolveMethodType(funTpe: Type, args: List[Type])(using Context): Type =
      funTpe.widenOverloads match
        case funTpe: MethodType =>
          // TODO: substitute parameters when dependent
          funTpe.resultType
        case tpe =>
          throw NonMethodReferenceException(s"application of args ${args.mkString} to $tpe")

    protected final def calculateType(using Context): Type =
      val original = resolveMethodType(fun.tpe, args.map(_.tpe))
      // If the resolved method type is not methodic, then it is the final result type.
      // in this case, check the method part to see if it is a constructor selection.
      // if it is, then the result type is the type of new.
      original match
        case partial: MethodicType => partial // Nothing to do here, it is partially applied.
        case _ =>
          methPart(fun) match
            case Select(newObj @ New(_), SignedName(nme.Constructor, _, nme.Constructor)) =>
              newObj.tpe
            case _ =>
              original

    override final def withSpan(span: Span): Apply = Apply(fun, args)(span)

  /** fun[args] */
  case class TypeApply(fun: Tree, args: List[TypeTree])(span: Span) extends Tree(span) {

    private def resolvePolyType(funTpe: Type, args: List[Type])(using Context): Type =
      funTpe.widenOverloads match
        case funTpe: PolyType =>
          funTpe.instantiate(args)
        case tpe =>
          throw NonMethodReferenceException(s"type application of args ${args.mkString} to $tpe")

    protected final def calculateType(using Context): Type =
      resolvePolyType(fun.tpe, args.map(_.toType))

    override final def withSpan(span: Span): TypeApply = TypeApply(fun, args)(span)
  }

  /** new tpt, but no constructor call */
  case class New(tpt: TypeTree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      tpt.toType

    override final def withSpan(span: Span): New = New(tpt)(span)
  }

  /** expr : tpt */
  case class Typed(expr: Tree, tpt: TypeTree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      tpt.toType

    override final def withSpan(span: Span): Typed = Typed(expr, tpt)(span)
  }

  /** name = arg, outside a parameter list */
  case class Assign(lhs: Tree, rhs: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      defn.UnitType

    override final def withSpan(span: Span): Assign = Assign(lhs, rhs)(span)
  }

  /** name = arg, in a parameter list */
  case class NamedArg(name: Name, arg: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      arg.tpe

    override final def withSpan(span: Span): NamedArg = NamedArg(name, arg)(span)
  }

  /** { stats; expr } */
  case class Block(stats: List[Tree], expr: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      expr.tpe

    override final def withSpan(span: Span): Block = Block(stats, expr)(span)
  }

  /** if cond then thenp else elsep */
  case class If(cond: Tree, thenPart: Tree, elsePart: Tree)(span: Span) extends Tree(span) {
    def isInline = false

    protected final def calculateType(using Context): Type =
      OrType(thenPart.tpe, elsePart.tpe)

    override def withSpan(span: Span): If = If(cond, thenPart, elsePart)(span)
  }

  class InlineIf(cond: Tree, thenPart: Tree, elsePart: Tree)(span: Span) extends If(cond, thenPart, elsePart)(span) {
    override def isInline = true
    override def toString = s"InlineIf($cond, $thenPart, $elsePart)"

    override final def withSpan(span: Span): InlineIf = InlineIf(cond, thenPart, elsePart)(span)
  }

  /**  @param meth   A reference to the method.
    *  @param tpt    Not an EmptyTree only if the lambda's type is a SAMtype rather than a function type.
    */
  case class Lambda(meth: Tree, tpt: TypeTree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      if tpt == EmptyTypeTree then
        ??? // TODO Resolve the method's type to construct the appropriate scala.FunctionN type
      else tpt.toType

    override final def withSpan(span: Span): Lambda = Lambda(meth, tpt)(span)
  }

  /** selector match { cases } */
  case class Match(selector: Tree, cases: List[CaseDef])(span: Span) extends Tree(span) {
    def isInline = false

    protected final def calculateType(using Context): Type =
      cases.map(_.tpe).reduce(OrType(_, _))

    override def withSpan(span: Span): Match = Match(selector, cases)(span)
  }

  class InlineMatch(selector: Tree, cases: List[CaseDef])(span: Span) extends Match(selector, cases)(span) {
    override def isInline = true
    override def toString = s"InlineMatch($selector, $cases)"

    override final def withSpan(span: Span): InlineMatch = InlineMatch(selector, cases)(span)
  }

  /** case pattern if guard => body; only appears as child of a Match and Try */
  case class CaseDef(pattern: Tree, guard: Tree, body: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      body.tpe

    override final def withSpan(span: Span): CaseDef = CaseDef(pattern, guard, body)(span)
  }

  /** pattern in {@link Unapply} */
  case class Bind(name: Name, body: Tree, override val symbol: TermSymbol)(span: Span)
      extends Tree(span)
      with DefTree(symbol) {
    protected final def calculateType(using Context): Type =
      NoType

    override final def withSpan(span: Span): Bind = Bind(name, body, symbol)(span)
  }

  /** tree_1 | ... | tree_n */
  case class Alternative(trees: List[Tree])(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): Alternative = Alternative(trees)(span)
  }

  /** `extractor(patterns)` in a pattern:
    *  @param fun       is `extractor.unapply` (or, for backwards compatibility, `extractor.unapplySeq`)
    *                   possibly with type parameters
    *  @param implicits Any implicit parameters passed to the unapply after the selector
    *  @param patterns  The argument patterns in the pattern match.
    *
    *  It is typed with same type as first `fun` argument
    *  Given a match selector `sel` a pattern UnApply(fun, implicits, patterns) is roughly translated as follows
    *
    *    val result = fun(sel)(implicits)
    *    if (result.isDefined) "match patterns against result"
    */
  case class Unapply(fun: Tree, implicits: List[Tree], patterns: List[Tree])(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type = NoType

    override final def withSpan(span: Span): Unapply = Unapply(fun, implicits, patterns)(span)
  }

  /** Seq(elems)
    *  @param  tpt  The element type of the sequence.
    */
  case class SeqLiteral(elems: List[Tree], elemtpt: TypeTree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      defn.SeqTypeOf(elemtpt.toType)

    override final def withSpan(span: Span): SeqLiteral = SeqLiteral(elems, elemtpt)(span)
  }

  /** while (cond) { body } */
  case class While(cond: Tree, body: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      defn.UnitType

    override final def withSpan(span: Span): While = While(cond, body)(span)
  }

  /** throw expr */
  case class Throw(expr: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      defn.NothingType

    override final def withSpan(span: Span): Throw = Throw(expr)(span)
  }

  /** try block catch cases finally finalizer */
  case class Try(expr: Tree, cases: List[CaseDef], finalizer: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      cases.foldLeft(expr.tpe)((prev, caze) => OrType(prev, caze.tpe))

    override final def withSpan(span: Span): Try = Try(expr, cases, finalizer)(span)
  }

  case class Literal(constant: Constant)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      ConstantType(constant)

    override final def withSpan(span: Span): Literal = Literal(constant)(span)
  }

  case class Return(expr: Tree, from: Tree)(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      defn.NothingType

    override final def withSpan(span: Span): Return = Return(expr, from)(span)
  }

  /** A tree representing inlined code.
    *
    * @param expr
    *   The inlined tree, minus bindings.
    * @param caller
    *   The toplevel class from which the call was inlined.
    * @param bindings
    *   Bindings for proxies to be used in the inlined code
    *
    * The full inlined code is equivalent to
    *
    * { bindings; expr }
    */
  case class Inlined(expr: Tree, caller: TypeIdent, bindings: List[Tree])(span: Span) extends Tree(span) {
    protected final def calculateType(using Context): Type =
      // TODO? Do we need to do type avoidance on expr using the bindings, like dotc does?
      expr.tpe

    override final def withSpan(span: Span): Inlined = Inlined(expr, caller, bindings)(span)
  }

  case object EmptyTree extends Tree(NoSpan) {
    protected final def calculateType(using Context): Type =
      NoType

    override final def withSpan(span: Span): Tree = EmptyTree
  }

}
