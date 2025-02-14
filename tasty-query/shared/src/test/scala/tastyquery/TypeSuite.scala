package tastyquery

import scala.collection.mutable

import tastyquery.Contexts.*
import tastyquery.Flags.*
import tastyquery.Names.*
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Types.*

import Paths.*

class TypeSuite extends UnrestrictedUnpicklingSuite {
  def assertIsSymbolWithPath(path: DeclarationPath)(actualSymbol: Symbol)(using Context): Unit = {
    val expectedSymbol = resolve(path)
    assert(actualSymbol eq expectedSymbol, clues(actualSymbol, expectedSymbol))
  }

  extension [T](elems: List[T])
    def isListOf(tests: (T => Boolean)*)(using Context): Boolean =
      elems.corresponds(tests)((t, test) => test(t))

  extension (tpe: Type)
    def isAny(using Context): Boolean = tpe.isRef(resolve(name"scala" / tname"Any"))

    def isNothing(using Context): Boolean = tpe.isRef(resolve(name"scala" / tname"Nothing"))

    def isWildcard(using Context): Boolean =
      isBounded(_.isNothing, _.isAny)

    def isBounded(lo: Type => Boolean, hi: Type => Boolean)(using Context): Boolean = tpe match
      case tpe: WildcardTypeBounds => lo(tpe.bounds.low) && hi(tpe.bounds.high)
      case _                       => false

    def isIntersectionOf(tpes: (Type => Boolean)*)(using Context): Boolean =
      def parts(tpe: Type): List[Type] = tpe match
        case tpe: AndType => tpe.parts
        case tpe: Type    => tpe :: Nil
      parts(tpe.widen).isListOf(tpes*)

    def isApplied(cls: Type => Boolean, argRefs: Seq[Type => Boolean])(using Context): Boolean =
      tpe.widen match
        case app: AppliedType if cls(app.tycon) =>
          app.args.corresponds(argRefs)((arg, argRef) => argRef(arg))
        case _ => false

    def isArrayOf(arg: Type => Boolean)(using Context): Boolean =
      isApplied(_.isRef(resolve(name"scala" / tname"Array")), Seq(arg))

  testWithContext("apply-recursive") {
    val RecApply = name"simple_trees" / tname"RecApply"

    val gcdSym = resolve(RecApply / name"gcd")
    val NumClass = resolve(RecApply / tname"Num")

    val Some(gcdTree @ _: DefDef) = gcdSym.tree: @unchecked

    var recCallCount = 0

    gcdTree.walkTree { tree =>
      tree match
        case recCall @ Apply(gcdRef @ Select(_, SignedName(SimpleName("gcd"), _, _)), _) =>
          recCallCount += 1

          assert(gcdRef.tpe.isRef(gcdSym), clue(gcdRef))

          assert(recCall.tpe.isRef(NumClass), clue(recCall))
        case _ => ()
    }

    assert(recCallCount == 1)

  }

  testWithContext("java.lang.String") {
    val StringClass = resolve(name"java" / name"lang" / tname"String").asClass
    val CharClass = resolve(name"scala" / tname"Char").asClass

    val charAt = StringClass.getDecl(name"charAt").get.asTerm
    val tpe = charAt.declaredType.asInstanceOf[MethodType]
    assert(clue(tpe.resultType).isRef(CharClass))
  }

  testWithContext("scala.compiletime.Error parents") {
    // #179 The parents of Error contain a TypeRef(PackageRef(scala), "Serializable")

    val ProductClass = resolve(name"scala" / tname"Product").asClass
    val SerializableClass = resolve(name"java" / name"io" / tname"Serializable").asClass
    val CompTimeErrorClass = resolve(name"scala" / name"compiletime" / name"testing" / tname"Error").asClass

    val parentClasses = CompTimeErrorClass.parentClasses
    assert(clue(parentClasses) == List(defn.ObjectClass, ProductClass, SerializableClass))
  }

  def applyOverloadedTest(name: String)(callMethod: String, paramCls: DeclarationPath)(using munit.Location): Unit =
    testWithContext(name) {
      val OverloadedApply = name"simple_trees" / tname"OverloadedApply"

      val callSym = resolve(OverloadedApply / termName(callMethod))
      val Acls = resolve(paramCls)
      val UnitClass = resolve(name"scala" / tname"Unit")

      val Some(callTree @ _: DefDef) = callSym.tree: @unchecked

      var callCount = 0

      callTree.walkTree { tree =>
        tree match
          case app @ Apply(fooRef @ Select(_, SignedName(SimpleName("foo"), _, _)), _) =>
            callCount += 1
            assert(app.tpe.isRef(UnitClass), clue(app)) // todo: resolve overloaded
            val fooSym = fooRef.tpe.termSymbol.asTerm
            val List(Left(List(aRef)), _*) = fooSym.paramRefss: @unchecked
            assert(aRef.isRef(Acls), clues(Acls.fullName, aRef))
          case _ => ()
      }

      assert(callCount == 1)
    }

  applyOverloadedTest("apply-overloaded-int")("callA", name"scala" / tname"Int")
  applyOverloadedTest("apply-overloaded-gen")("callB", name"simple_trees" / tname"OverloadedApply" / tname"Box")
  applyOverloadedTest("apply-overloaded-nestedObj")(
    "callC",
    name"simple_trees" / tname"OverloadedApply" / tname"Foo" / obj / name"Bar"
  )
  // applyOverloadedTest("apply-overloaded-arrayObj")("callD", name"scala" / tname"Array") // TODO: re-enable when we add types to scala 2 symbols
  applyOverloadedTest("apply-overloaded-byName")("callE", name"simple_trees" / tname"OverloadedApply" / tname"Num")

  testWithContext("typeapply-recursive") {
    val RecApply = name"simple_trees" / tname"RecApply"

    val evalSym = resolve(RecApply / name"eval").asTerm
    val ExprClass = resolve(RecApply / tname"Expr")
    val NumClass = resolve(RecApply / tname"Num")
    val BoolClass = resolve(RecApply / tname"Bool")

    val evalParamRefss = evalSym.paramRefss

    val List(Right(List(TRef @ _)), Left(List(eRef))) = evalParamRefss: @unchecked

    val Some(evalTree @ _: DefDef) = evalSym.tree: @unchecked

    var recCallCount = 0

    evalTree.walkTree { tree =>
      tree match
        case recCall @ Apply(TypeApply(evalRef @ Select(_, SignedName(SimpleName("eval"), _, _)), List(targ)), _) =>
          recCallCount += 1

          assert(evalRef.tpe.isRef(evalSym), clue(evalRef))

          /* Because of GADT reasoning, the first two recursive call implicitly
           * have a [Num] type parameter, while the latter two have [Bool].
           */
          val expectedTargClass =
            if recCallCount <= 2 then NumClass
            else BoolClass

          assert(clue(targ).toType.isRef(expectedTargClass))
          assert(recCall.tpe.isRef(expectedTargClass), clue(recCall))
        case _ => ()
    }

    assert(recCallCount == 4) // 4 calls in the body of `eval`

  }

  testWithContext("basic-local-val") {
    val AssignPath = name"simple_trees" / tname"Assign"

    val fSym = resolve(AssignPath / name"f").asTerm
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    val List(Left(List(xParamDef))) = fTree.paramLists: @unchecked

    val IntClass = resolve(name"scala" / tname"Int")
    assert(xParamDef.symbol.asTerm.declaredType.isRef(IntClass))

    fSym.declaredType match
      case mt: MethodType =>
        assert(mt.paramTypes.sizeIs == 1 && mt.paramTypes.head.isRef(IntClass))
        assert(mt.resultType.isRef(IntClass))
      case _ =>
        fail(s"$fSym does not have a MethodType", clues(fSym.declaredType))

    val x = SimpleName("x")
    val y = SimpleName("y")

    var xCount = 0
    var yCount = 0

    fTree.walkTree { tree =>
      tree match {
        case Ident(`x`) =>
          xCount += 1
          assert(tree.tpe.isOfClass(IntClass), clue(tree.tpe))
        case Ident(`y`) =>
          yCount += 1
          assert(tree.tpe.isOfClass(IntClass), clue(tree.tpe))
        case _ =>
          ()
      }
    }

    assert(xCount == 2, clue(xCount))
    assert(yCount == 1, clue(yCount))
  }

  testWithContext("term-ref") {
    val RepeatedPath = name"simple_trees" / tname"Repeated"

    val fSym = resolve(RepeatedPath / name"f")
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    var bitSetIdentCount = 0

    fTree.walkTree { tree =>
      tree match {
        case Ident(SimpleName("BitSet")) =>
          bitSetIdentCount += 1
          val sym = tree.tpe.asInstanceOf[TermRef].symbol
          assert(sym.name == name"BitSet", clue(sym.name.toDebugString))
          ()
        case _ =>
          ()
      }
    }

    assert(bitSetIdentCount == 1, clue(bitSetIdentCount))
  }

  testWithContext("free-ident") {
    val MatchPath = name"simple_trees" / tname"Match"

    val fSym = resolve(MatchPath / name"f")
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    val List(Left(List(xParamDef))) = fTree.paramLists: @unchecked

    val IntClass = resolve(name"scala" / tname"Int")
    assert(xParamDef.symbol.asTerm.declaredType.isRef(IntClass))

    var freeIdentCount = 0

    fTree.walkTree { tree =>
      tree match {
        case tree: FreeIdent =>
          freeIdentCount += 1
          assert(tree.name == nme.Wildcard, clue(tree.name))
          assert(tree.tpe.isOfClass(IntClass), clue(tree.tpe))
        case _ =>
          ()
      }
    }

    assert(freeIdentCount == 2, clue(freeIdentCount))
  }

  testWithContext("return") {
    val ReturnPath = name"simple_trees" / tname"Return"

    val withReturnSym = resolve(ReturnPath / name"withReturn")
    val withReturnTree = withReturnSym.tree.get.asInstanceOf[DefDef]

    var returnCount = 0

    withReturnTree.walkTree { tree =>
      tree match {
        case Return(expr, from: Ident) =>
          returnCount += 1
          assert(from.tpe.isRef(withReturnSym), clue(from.tpe))
          assert(tree.tpe.isRef(resolve(name"scala" / tname"Nothing")))
        case _ =>
          ()
      }
    }

    assert(returnCount == 1, clue(returnCount))
  }

  testWithContext("assign") {
    val AssignPath = name"simple_trees" / tname"Assign"

    val fSym = resolve(AssignPath / name"f")
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    var assignCount = 0

    fTree.walkTree { tree =>
      tree match {
        case Assign(lhs, rhs) =>
          assignCount += 1
          val UnitClass = resolve(name"scala" / tname"Unit")
          assert(tree.tpe.isOfClass(UnitClass), clue(tree.tpe))
        case _ =>
          ()
      }
    }

    assert(assignCount == 1, clue(assignCount))
  }

  testWithContext("basic-scala2-types") {
    val ScalaRange = name"scala" / name"collection" / name"immutable" / tname"Range"

    val RangeClass = resolve(ScalaRange).asClass

    val BooleanClass = resolve(name"scala" / tname"Boolean")
    val IntClass = resolve(name"scala" / tname"Int")
    val Function1Class = resolve(name"scala" / tname"Function1")
    val IndexedSeqClass = resolve(name"scala" / name"collection" / name"immutable" / tname"IndexedSeq")

    // val start: Int
    val startSym = RangeClass.getDecl(name"start").get.asTerm
    assert(startSym.declaredType.isOfClass(IntClass), clue(startSym.declaredType))
    assert(startSym.declaredType.isInstanceOf[ExprType], clue(startSym.declaredType)) // should this be TypeRef?

    // val isEmpty: Boolean
    val isEmptySym = RangeClass.getDecl(name"isEmpty").get.asTerm
    assert(isEmptySym.declaredType.isOfClass(BooleanClass), clue(isEmptySym.declaredType))
    assert(isEmptySym.declaredType.isInstanceOf[ExprType], clue(isEmptySym.declaredType)) // should this be TypeRef?

    // def isInclusive: Boolean
    val isInclusiveSym = RangeClass.getDecl(name"isInclusive").get.asTerm
    assert(isInclusiveSym.declaredType.isOfClass(BooleanClass), clue(isInclusiveSym.declaredType))
    assert(isInclusiveSym.declaredType.isInstanceOf[ExprType], clue(isInclusiveSym.declaredType))

    // def by(step: Int): Range
    locally {
      val bySym = RangeClass.getDecl(name"by").get.asTerm
      val mt = bySym.declaredType.asInstanceOf[MethodType]
      assertEquals(List[TermName](name"step"), mt.paramNames, clue(mt.paramNames))
      assert(mt.paramTypes.sizeIs == 1)
      assert(mt.paramTypes.head.isOfClass(IntClass), clue(mt.paramTypes.head))
      assert(mt.resultType.isOfClass(RangeClass), clue(mt.resultType))
    }

    // def map[B](f: Int => B): IndexedSeq[B]
    locally {
      val mapSym = RangeClass.getDecl(name"map").get.asTerm
      val pt = mapSym.declaredType.asInstanceOf[PolyType]
      val mt = pt.resultType.asInstanceOf[MethodType]
      assertEquals(List[TypeName](name"B".toTypeName), pt.paramNames, clue(pt.paramNames))
      assert(pt.paramTypeBounds.sizeIs == 1)
      assertEquals(List[TermName](name"f"), mt.paramNames, clue(mt.paramNames))
      assert(mt.paramTypes.sizeIs == 1)
      assert(mt.paramTypes.head.isOfClass(Function1Class), clue(mt.paramTypes.head))
      assert(mt.resultType.isOfClass(IndexedSeqClass), clue(mt.resultType))
    }
  }

  testWithContext("basic-java-class-dependency") {
    val BoxedJava = name"javacompat" / tname"BoxedJava"
    val JavaDefined = name"javadefined" / tname"JavaDefined"

    val boxedSym = resolve(BoxedJava / name"boxed").asTerm

    val (JavaDefinedRef @ _: TypeRef) = boxedSym.declaredType: @unchecked

    assertIsSymbolWithPath(JavaDefined)(JavaDefinedRef.symbol)
  }

  testWithContext("bag-of-java-definitions") {
    val BagOfJavaDefinitions = name"javadefined" / tname"BagOfJavaDefinitions"

    val IntClass = resolve(name"scala" / tname"Int")
    val UnitClass = resolve(name"scala" / tname"Unit")

    def testDef(path: DeclarationPath)(op: TermSymbol => Unit): Unit =
      op(resolve(path).asTerm)

    testDef(BagOfJavaDefinitions / name"x") { x =>
      assert(x.declaredType.isRef(IntClass))
    }

    testDef(BagOfJavaDefinitions / name"recField") { recField =>
      assert(recField.declaredType.isRef(resolve(BagOfJavaDefinitions)))
    }

    testDef(BagOfJavaDefinitions / name"printX") { printX =>
      val tpe = printX.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isRef(UnitClass))
    }

    testDef(BagOfJavaDefinitions / name"<init>") { ctor =>
      val tpe = ctor.declaredType.asInstanceOf[MethodType]
      assert(tpe.paramTypes.head.isRef(IntClass))
      assert(tpe.resultType.isRef(UnitClass))
    }

    testDef(BagOfJavaDefinitions / name"wrapXArray") { wrapXArray =>
      val tpe = wrapXArray.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isArrayOf(_.isRef(IntClass)))
    }

    testDef(BagOfJavaDefinitions / name"arrIdentity") { arrIdentity =>
      val tpe = arrIdentity.declaredType.asInstanceOf[MethodType]
      val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")
      assert(tpe.paramInfos.head.isArrayOf(_.isRef(JavaDefinedClass)))
      assert(tpe.resultType.isArrayOf(_.isRef(JavaDefinedClass)))
    }

    testDef(BagOfJavaDefinitions / name"processBuilder") { processBuilder =>
      val tpe = processBuilder.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isInstanceOf[TypeRef]) // do not call isRef, as we do not have the java lib
    }

  }

  testWithContext("bag-of-generic-java-definitions[signatures]") {
    val BagOfGenJavaDefinitions = name"javadefined" / tname"BagOfGenJavaDefinitions"
    val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")
    val GenericJavaClass = resolve(name"javadefined" / tname"GenericJavaClass")
    val JavaInterface1 = resolve(name"javadefined" / tname"JavaInterface1")
    val JavaInterface2 = resolve(name"javadefined" / tname"JavaInterface2")
    val ExceptionClass = resolve(name"java" / name"lang" / tname"Exception")

    def testDef(path: DeclarationPath)(op: TermSymbol => Unit): Unit =
      op(resolve(path).asTerm)

    extension (tpe: Type)
      def isGenJavaClassOf(arg: Type => Boolean)(using Context): Boolean =
        tpe.isApplied(_.isRef(GenericJavaClass), List(arg))

    testDef(BagOfGenJavaDefinitions / name"x") { x =>
      assert(x.declaredType.isGenJavaClassOf(_.isRef(JavaDefinedClass)))
    }

    testDef(BagOfGenJavaDefinitions / name"getX") { getX =>
      val tpe = getX.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isGenJavaClassOf(_.isRef(JavaDefinedClass)))
    }

    testDef(BagOfGenJavaDefinitions / name"getXArray") { getXArray =>
      val tpe = getXArray.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isArrayOf(_.isGenJavaClassOf(_.isRef(JavaDefinedClass))))
    }

    testDef(BagOfGenJavaDefinitions / name"printX") { printX =>
      val tpe = printX.declaredType.asInstanceOf[PolyType]
      assert(tpe.paramTypeBounds.head.high.isRef(ExceptionClass))
    }

    testDef(BagOfGenJavaDefinitions / name"recTypeParams") { recTypeParams =>
      val tpe = recTypeParams.declaredType.asInstanceOf[TypeLambdaType]
      val List(tparamRefA, tparamRefY) = tpe.paramRefs: @unchecked
      assert(tparamRefA.bounds.high.isGenJavaClassOf(_ == tparamRefY))
    }

    testDef(BagOfGenJavaDefinitions / name"refInterface") { refInterface =>
      val tpe = refInterface.declaredType.asInstanceOf[TypeLambdaType]
      val List(tparamRefA) = tpe.paramRefs: @unchecked
      assert(
        tparamRefA.bounds.high.isIntersectionOf(_.isAny, _.isRef(JavaInterface1), _.isRef(JavaInterface2)),
        clues(tparamRefA.bounds)
      )
    }

    testDef(BagOfGenJavaDefinitions / name"genraw") { genraw =>
      /* Raw types are not really supported (see #80). They are read and
       * stored as if they were *monomorphic* class references, i.e., TypeRef's
       * without any AppliedType.
       */
      assert(genraw.declaredType.isRef(GenericJavaClass))
    }

    testDef(BagOfGenJavaDefinitions / name"mixgenraw") { mixgenraw =>
      // Same comment about raw types.
      assert(mixgenraw.declaredType.isGenJavaClassOf(_.isRef(GenericJavaClass)))
    }

    testDef(BagOfGenJavaDefinitions / name"genwild") { genwild =>
      assert(genwild.declaredType.isGenJavaClassOf(_.isWildcard))
    }

    testDef(BagOfGenJavaDefinitions / name"gencovarient") { gencovarient =>
      assert(gencovarient.declaredType.isGenJavaClassOf(_.isBounded(_.isNothing, _.isRef(JavaDefinedClass))))
    }

    testDef(BagOfGenJavaDefinitions / name"gencontravarient") { gencontravarient =>
      assert(gencontravarient.declaredType.isGenJavaClassOf(_.isBounded(_.isRef(JavaDefinedClass), _.isAny)))
    }
  }

  testWithContext("java-class-parents") {
    val SubJavaDefinedClass = resolve(name"javadefined" / tname"SubJavaDefined").asClass
    val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")
    val JavaInterface1Class = resolve(name"javadefined" / tname"JavaInterface1")
    val JavaInterface2Class = resolve(name"javadefined" / tname"JavaInterface2")

    assert(
      SubJavaDefinedClass.parents
        .isListOf(_.isRef(JavaDefinedClass), _.isRef(JavaInterface1Class), _.isRef(JavaInterface2Class))
    )
  }

  testWithContext("Parents of special classes") {
    assert(clue(defn.AnyClass.parentClasses) == Nil)
    assert(clue(defn.MatchableClass.parentClasses) == List(defn.AnyClass))
    assert(clue(defn.ObjectClass.parentClasses) == List(defn.AnyClass, defn.MatchableClass))
    assert(clue(defn.AnyValClass.parentClasses) == List(defn.AnyClass, defn.MatchableClass))
    assert(clue(defn.NullClass.parentClasses) == List(defn.AnyClass, defn.MatchableClass))
    assert(clue(defn.NothingClass.parentClasses) == List(defn.AnyClass))
  }

  testWithContext("java-class-signatures-[RecClass]") {
    val RecClass = resolve(name"javadefined" / tname"RecClass").asClass
    val ObjectClass = resolve(name"java" / name"lang" / tname"Object")

    assert(RecClass.parents.isListOf(_.isRef(ObjectClass)))
  }

  testWithContext("java-class-signatures-[SubRecClass]") {
    val SubRecClass = resolve(name"javadefined" / tname"SubRecClass").asClass
    val RecClass = resolve(name"javadefined" / tname"RecClass")
    val JavaInterface1 = resolve(name"javadefined" / tname"JavaInterface1")

    val List(tparamT) = SubRecClass.typeParams: @unchecked

    assert(
      SubRecClass.parents.isListOf(
        _.isApplied(_.isRef(RecClass), List(_.isApplied(_.isRef(SubRecClass), List(_.isRef(tparamT))))),
        _.isRef(JavaInterface1)
      )
    )
  }

  testWithContext("select-method-from-java-class") {
    val BoxedJava = name"javacompat" / tname"BoxedJava"
    val getX = name"javadefined" / tname"JavaDefined" / name"getX"

    val xMethodSym = resolve(BoxedJava / name"xMethod")

    val Some(DefDef(_, _, _, Apply(getXSelection, _), _)) = xMethodSym.tree: @unchecked

    val (getXRef @ _: TermRef) = getXSelection.tpe: @unchecked

    assertIsSymbolWithPath(getX)(getXRef.symbol)
  }

  testWithContext("select-field-from-java-class") {
    val BoxedJava = name"javacompat" / tname"BoxedJava"
    val x = name"javadefined" / tname"JavaDefined" / name"x"

    val xFieldSym = resolve(BoxedJava / name"xField")

    val Some(DefDef(_, _, _, xSelection, _)) = xFieldSym.tree: @unchecked

    val (xRef @ _: TermRef) = xSelection.tpe: @unchecked

    assertIsSymbolWithPath(x)(xRef.symbol)
  }

  testWithContext("basic-scala-2-stdlib-class-dependency") {
    val BoxedCons = name"scala2compat" / tname"BoxedCons"
    val :: = name"scala" / name"collection" / name"immutable" / tname"::"

    val ConsClass = resolve(::)
    val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")

    val boxedSym = resolve(BoxedCons / name"boxed").asTerm

    val app = boxedSym.declaredType.asInstanceOf[AppliedType]
    assert(clue(app.tycon).isOfClass(ConsClass))
    assert(clue(app.args).isListOf(_.isOfClass(JavaDefinedClass)))
  }

  testWithContext("select-method-from-scala-2-stdlib-class") {
    val BoxedCons = name"scala2compat" / tname"BoxedCons"
    val canEqual = name"scala" / name"collection" / tname"Seq" / name"canEqual"

    val AnyClass = resolve(name"scala" / tname"Any")
    val BooleanClass = resolve(name"scala" / tname"Boolean")

    val fooSym = resolve(BoxedCons / name"foo")

    val Some(DefDef(_, _, _, Apply(canEqualSelection, _), _)) = fooSym.tree: @unchecked

    val underlyingType = canEqualSelection.tpe match
      case termRef: TermRef => termRef.underlying
      case tpe              => fail("expected TermRef", clues(tpe))

    val mt = underlyingType.asInstanceOf[MethodType]
    assertEquals(List[TermName](name"that"), mt.paramNames, clue(mt.paramNames))
    assert(mt.paramTypes.sizeIs == 1, clue(mt.paramTypes))
    assert(mt.paramTypes.head.isOfClass(AnyClass), clue(mt.paramTypes.head))
    assert(mt.resultType.isOfClass(BooleanClass), clue(mt.resultType))
  }

  testWithContext("select-field-from-tasty-in-other-package:dependency-from-class-file") {
    val BoxedConstants = name"crosspackagetasty" / tname"BoxedConstants"
    val unitVal = name"simple_trees" / tname"Constants" / name"unitVal"

    val boxedUnitValSym = resolve(BoxedConstants / name"boxedUnitVal")

    val Some(DefDef(_, _, _, unitValSelection, _)) = boxedUnitValSym.tree: @unchecked

    val (unitValRef @ _: TermRef) = unitValSelection.tpe: @unchecked

    assertIsSymbolWithPath(unitVal)(unitValRef.symbol)
  }

  testWithContext("select-method-from-java-class-same-package-as-tasty") {
    // This tests reading top level classes in the same package, defined by
    // both Java and Tasty. If we strictly require that all symbols are defined
    // exactly once, then we must be careful to not redefine `ScalaBox`/`JavaBox`
    // when scanning a package from the classpath.

    val ScalaBox = name"mixjavascala" / tname"ScalaBox"
    val getX = name"mixjavascala" / tname"JavaBox" / name"getX"

    val xMethodSym = resolve(ScalaBox / name"xMethod")

    val Some(DefDef(_, _, _, Apply(getXSelection, _), _)) = xMethodSym.tree: @unchecked

    val (getXRef @ _: TermRef) = getXSelection.tpe: @unchecked

    assertIsSymbolWithPath(getX)(getXRef.symbol)
  }

  testWithContext("select-field-from-generic-class") {
    val GenClass = resolve(name"simple_trees" / tname"GenericClass").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testField").get.tree: @unchecked

    val Select(qual, fieldName) = body: @unchecked

    assert(clue(qual.tpe).isApplied(_.isOfClass(GenClass), List(_.isOfClass(IntClass))))
    assertEquals(fieldName, name"field")
    assert(clue(body.tpe).isOfClass(IntClass))
  }

  testWithContext("select-getter-from-generic-class") {
    val GenClass = resolve(name"simple_trees" / tname"GenericClass").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testGetter").get.tree: @unchecked

    val Select(qual, getterName) = body: @unchecked

    assert(clue(qual.tpe).isApplied(_.isOfClass(GenClass), List(_.isOfClass(IntClass))))
    assertEquals(getterName, name"getter")
    assert(clue(body.tpe).isOfClass(IntClass))
  }

  testWithContext("select-and-apply-method-from-generic-class") {
    val GenClass = resolve(name"simple_trees" / tname"GenericClass").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testMethod").get.tree: @unchecked

    val Apply(fun @ Select(qual, methodName), List(arg)) = body: @unchecked

    assert(clue(qual.tpe).isApplied(_.isOfClass(GenClass), List(_.isOfClass(IntClass))))
    methodName match {
      case SignedName(_, _, simpleName) => assertEquals(simpleName, name"method")
    }
    fun.tpe.widen match {
      case mt: MethodType =>
        assert(clue(mt.paramNames) == List(name"x"))
        assert(clue(mt.paramTypes.head).isOfClass(IntClass))
        assert(clue(mt.resultType).isOfClass(IntClass))
    }
    assert(clue(body.tpe).isOfClass(IntClass))
  }

  testWithContext("select-and-apply-poly-method") {
    val GenMethod = resolve(name"simple_trees" / tname"GenericMethod").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testGenericMethod").get.tree: @unchecked

    val Apply(tapp @ TypeApply(fun @ Select(qual, methodName), List(targ)), List(arg)) = body: @unchecked

    assert(clue(qual.tpe).isOfClass(GenMethod))
    methodName match {
      case SignedName(_, _, simpleName) => assertEquals(simpleName, name"identity")
    }
    tapp.tpe.widen match {
      case mt: MethodType =>
        assert(clue(mt.paramNames) == List(name"x"))
        assert(clue(mt.paramTypes.head).isOfClass(IntClass))
        assert(clue(mt.resultType).isOfClass(IntClass))
    }
    assert(clue(body.tpe).isOfClass(IntClass))
  }

  testWithContext("console-outvar-issue-78") {
    val Console = resolve(name"scala" / tname"Console" / obj).asClass
    val DynamicVariable = resolve(name"scala" / name"util" / tname"DynamicVariable").asClass

    val outVar = Console.getDecl(name"outVar").get.asTerm
    assert(clue(outVar.declaredType).isApplied(_.isRef(DynamicVariable), List(_ => true)))
  }

  testWithContext("scala-predef-declared-type") {
    val predef = resolve(name"scala" / name"Predef").asTerm
    val Predef = resolve(name"scala" / tname"Predef" / obj)
    assert(clue(predef.declaredType).isRef(Predef))
  }

  testWithContext("scala.math.Ordering") {
    val OrderingModClass = resolve(name"scala" / name"math" / tname"Ordering" / obj).asClass
    assert(OrderingModClass.getDecl(name"by").isDefined)
  }

  testWithContext("scala.math.Ordering.IntOrdering") {
    val IntOrderingClass = resolve(name"scala" / name"math" / tname"Ordering" / obj / tname"IntOrdering").asClass
    val IntClass = resolve(name"scala" / tname"Int").asClass

    val compare = IntOrderingClass.getDecl(name"compare").get.asTerm
    val mt = compare.declaredType.asInstanceOf[MethodType]
    assert(clue(mt.paramTypes(0)).isRef(IntClass))
    assert(clue(mt.paramTypes(1)).isRef(IntClass))
    assert(clue(mt.resultType).isRef(IntClass))
  }

  testWithContext("scala.math.Ordering.Float.TotalOrdering") {
    val path = name"scala" / name"math" / tname"Ordering" / obj / tname"Float" / obj / tname"TotalOrdering"
    val FloatTotalOrderingClass = resolve(path).asClass
    val IntClass = resolve(name"scala" / tname"Int").asClass
    val FloatClass = resolve(name"scala" / tname"Float").asClass

    val compare = FloatTotalOrderingClass.getDecl(name"compare").get.asTerm
    val mt = compare.declaredType.asInstanceOf[MethodType]
    assert(clue(mt.paramTypes(0)).isRef(FloatClass))
    assert(clue(mt.paramTypes(1)).isRef(FloatClass))
    assert(clue(mt.resultType).isRef(IntClass))
  }

  testWithContext("read-scala2-type-ref-type") {
    val RichBoolean = resolve(name"scala" / name"runtime" / tname"RichBoolean").asClass
    val BooleanOrdering = resolve(name"scala" / name"math" / tname"Ordering" / obj / name"Boolean")
    val ord = RichBoolean.getDecl(name"ord").get.asTerm
    assert(clue(ord.declaredType).isRef(BooleanOrdering))
  }

  testWithContext("read-encoded-scala2-type-ref-type") {
    val Function1Class = resolve(name"scala" / tname"Function1").asClass
    val SerializableClass = resolve(name"java" / name"io" / tname"Serializable").asClass
    val TypeEqClass = resolve(name"scala" / tname"=:=").asClass
    val SubtypeClass = resolve(name"scala" / tname"<:<").asClass

    assert(clue(SubtypeClass.parentClasses) == List(defn.ObjectClass, Function1Class, SerializableClass))
    assert(clue(TypeEqClass.parentClasses) == List(SubtypeClass, SerializableClass))
  }

  testWithContext("scala2-type-alias") {
    val PredefString = resolve(name"scala" / tname"Predef" / obj / tname"String").asType
    val JLString = resolve(name"java" / name"lang" / tname"String")

    assert(clue(PredefString).isTypeAlias)
    assert(clue(PredefString.asInstanceOf[TypeMemberSymbol].aliasedType).isRef(JLString))
  }

  testWithContext("scala2-module-and-def-with-same-name") {
    val StringContext = resolve(name"scala" / tname"StringContext").asClass
    val sModuleClass = resolve(name"scala" / tname"StringContext" / tname"s" / obj)

    val sDecls = StringContext.getDecls(name"s")
    assert(clue(sDecls).sizeIs == 2)

    val (sModule, sDef) =
      if sDecls(0).is(Module) then (sDecls(0), sDecls(1))
      else
        assert(sDecls(1).is(Module))
        (sDecls(1), sDecls(0))

    assert(clue(sModule.asTerm.declaredType).isRef(sModuleClass))
    assert(clue(sDef.asTerm.declaredType).isInstanceOf[MethodType])
  }

  testWithContext("scala2-class-type-params") {
    val ListClass = resolve(name"scala" / name"collection" / name"immutable" / tname"List").asClass
    val ArrayClass = resolve(name"scala" / tname"Array").asClass

    val List(targList) = ListClass.typeParams: @unchecked
    // TODO Set flags ClassTypeParam on TypeParams
    //assert(clue(targList.flags).isAllOf(ClassTypeParam))

    val List(targArray) = ArrayClass.typeParams: @unchecked
    // TODO Set flags ClassTypeParam on TypeParams
    //assert(clue(targArray.flags).isAllOf(ClassTypeParam))
  }

  testWithContext("poly-type-in-higher-kinded") {
    val HigherKindedClass = resolve(name"simple_trees" / tname"HigherKinded").asClass
    val polyMethod = HigherKindedClass.getDecl(name"m").get.asTerm
    assert(polyMethod.declaredType.asInstanceOf[PolyType].resultType.isInstanceOf[MethodType])
  }

  testWithContext("scala.collection.:+") {
    // type parameter C <: SeqOps[A, CC, C]
    val `:+` = resolve(name"scala" / name"collection" / tname"package" / obj / tname":+" / obj).asClass
  }

  testWithContext("read-scala.collection.mutable.StringBuilder_after-force-scala-pkg") {
    val scala = resolve(RootPkg / name"scala").asPackage
    scala.declarations

    val StringBuilder = resolve(RootPkg / name"scala" / name"collection" / name"mutable" / tname"StringBuilder").asClass
  }

  testWithContext("linearization") {
    val OverridesPath = name"inheritance" / tname"Overrides" / obj
    val SuperMonoClass = resolve(OverridesPath / tname"SuperMono").asClass
    val SuperMonoTraitClass = resolve(OverridesPath / tname"SuperMonoTrait").asClass
    val MidMonoClass = resolve(OverridesPath / tname"MidMono").asClass
    val ChildMonoClass = resolve(OverridesPath / tname"ChildMono").asClass

    val linTail = defn.ObjectClass :: defn.MatchableClass :: defn.AnyClass :: Nil

    assert(clue(SuperMonoClass.linearization) == SuperMonoClass :: linTail)
    assert(clue(SuperMonoTraitClass.linearization) == SuperMonoTraitClass :: linTail)

    val expectedMidMonoLin = MidMonoClass :: SuperMonoTraitClass :: SuperMonoClass :: linTail
    assert(clue(MidMonoClass.linearization) == expectedMidMonoLin)
    assert(clue(ChildMonoClass.linearization) == ChildMonoClass :: expectedMidMonoLin)
  }

  testWithContext("overrides-mono-no-overloads") {
    val OverridesPath = name"inheritance" / tname"Overrides" / obj
    val SuperMonoClass = resolve(OverridesPath / tname"SuperMono").asClass
    val SuperMonoTraitClass = resolve(OverridesPath / tname"SuperMonoTrait").asClass
    val MidMonoClass = resolve(OverridesPath / tname"MidMono").asClass
    val ChildMonoClass = resolve(OverridesPath / tname"ChildMono").asClass

    val fooInSuper = SuperMonoClass.getDecl(name"foo").get.asTerm
    val fooInChild = ChildMonoClass.getDecl(name"foo").get.asTerm

    val barInSuperTrait = SuperMonoTraitClass.getDecl(name"bar").get.asTerm
    val barInChild = ChildMonoClass.getDecl(name"bar").get.asTerm

    val foobazInSuper = SuperMonoClass.getDecl(name"foobaz").get.asTerm
    val foobazInChild = ChildMonoClass.getDecl(name"foobaz").get.asTerm

    // From fooInSuper

    assert(clue(fooInSuper.overriddenSymbol(SuperMonoClass)) == Some(fooInSuper))
    assert(clue(fooInSuper.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(fooInSuper.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(fooInSuper.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(fooInSuper.overridingSymbol(SuperMonoClass)) == Some(fooInSuper))
    assert(clue(fooInSuper.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(fooInSuper.overridingSymbol(MidMonoClass)) == None)
    assert(clue(fooInSuper.overridingSymbol(ChildMonoClass)) == Some(fooInChild))

    assert(clue(fooInSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(fooInSuper.nextOverriddenSymbol) == None)

    // From fooInChild

    assert(clue(fooInChild.overriddenSymbol(SuperMonoClass)) == Some(fooInSuper))
    assert(clue(fooInChild.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(fooInChild.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(fooInChild.overriddenSymbol(ChildMonoClass)) == Some(fooInChild))

    assert(clue(fooInChild.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(fooInChild.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(fooInChild.overridingSymbol(MidMonoClass)) == None)
    assert(clue(fooInChild.overridingSymbol(ChildMonoClass)) == Some(fooInChild))

    assert(clue(fooInChild.allOverriddenSymbols.toList) == List(fooInSuper))
    assert(clue(fooInChild.nextOverriddenSymbol) == Some(fooInSuper))

    // From barInSuperTrait

    assert(clue(barInSuperTrait.overriddenSymbol(SuperMonoClass)) == None)
    assert(clue(barInSuperTrait.overriddenSymbol(SuperMonoTraitClass)) == Some(barInSuperTrait))
    assert(clue(barInSuperTrait.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(barInSuperTrait.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(barInSuperTrait.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(barInSuperTrait.overridingSymbol(SuperMonoTraitClass)) == Some(barInSuperTrait))
    assert(clue(barInSuperTrait.overridingSymbol(MidMonoClass)) == None)
    assert(clue(barInSuperTrait.overridingSymbol(ChildMonoClass)) == Some(barInChild))

    assert(clue(barInSuperTrait.allOverriddenSymbols.toList) == Nil)
    assert(clue(barInSuperTrait.nextOverriddenSymbol) == None)

    // From barInChild

    assert(clue(barInChild.overriddenSymbol(SuperMonoClass)) == None)
    assert(clue(barInChild.overriddenSymbol(SuperMonoTraitClass)) == Some(barInSuperTrait))
    assert(clue(barInChild.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(barInChild.overriddenSymbol(ChildMonoClass)) == Some(barInChild))

    assert(clue(barInChild.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(barInChild.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(barInChild.overridingSymbol(MidMonoClass)) == None)
    assert(clue(barInChild.overridingSymbol(ChildMonoClass)) == Some(barInChild))

    assert(clue(barInChild.allOverriddenSymbols.toList) == List(barInSuperTrait))
    assert(clue(barInChild.nextOverriddenSymbol) == Some(barInSuperTrait))

    // From foobazInSuper

    assert(clue(foobazInSuper.overriddenSymbol(SuperMonoClass)) == Some(foobazInSuper))
    assert(clue(foobazInSuper.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(foobazInSuper.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(foobazInSuper.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(foobazInSuper.overridingSymbol(SuperMonoClass)) == Some(foobazInSuper))
    assert(clue(foobazInSuper.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(foobazInSuper.overridingSymbol(MidMonoClass)) == None)
    assert(clue(foobazInSuper.overridingSymbol(ChildMonoClass)) == Some(foobazInChild))

    assert(clue(foobazInSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(foobazInSuper.nextOverriddenSymbol) == None)

    // From foobazInChild

    assert(clue(foobazInChild.overriddenSymbol(SuperMonoClass)) == Some(foobazInSuper))
    assert(clue(foobazInChild.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(foobazInChild.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(foobazInChild.overriddenSymbol(ChildMonoClass)) == Some(foobazInChild))

    assert(clue(foobazInChild.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(foobazInChild.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(foobazInChild.overridingSymbol(MidMonoClass)) == None)
    assert(clue(foobazInChild.overridingSymbol(ChildMonoClass)) == Some(foobazInChild))

    assert(clue(foobazInChild.allOverriddenSymbols.toList) == List(foobazInSuper))
    assert(clue(foobazInChild.nextOverriddenSymbol) == Some(foobazInSuper))
  }

  testWithContext("overrides-mono-overloads") {
    val OverridesPath = name"inheritance" / tname"Overrides" / obj
    val SuperMonoClass = resolve(OverridesPath / tname"SuperMono").asClass
    val SuperMonoTraitClass = resolve(OverridesPath / tname"SuperMonoTrait").asClass
    val MidMonoClass = resolve(OverridesPath / tname"MidMono").asClass
    val ChildMonoClass = resolve(OverridesPath / tname"ChildMono").asClass

    val IntClass = defn.IntClass
    val StringClass = defn.StringClass

    extension (meth: TermSymbol)
      def firstParamTypeIsRef(cls: Symbol): Boolean =
        meth.declaredType.asInstanceOf[MethodType].paramTypes.head.isRef(cls)
      def typeParamCountIs(count: Int): Boolean =
        meth.declaredType.asInstanceOf[PolyType].paramNames.sizeIs == count

    val overloadedInSuper = SuperMonoClass.getDecls(name"overloaded").map(_.asTerm)
    val intInSuper = overloadedInSuper.find(_.firstParamTypeIsRef(IntClass)).get
    val stringInSuper = overloadedInSuper.find(_.firstParamTypeIsRef(StringClass)).get

    val overloadedInChild = ChildMonoClass.getDecls(name"overloaded").map(_.asTerm)
    val intInChild = overloadedInChild.find(_.firstParamTypeIsRef(IntClass)).get
    val stringInChild = overloadedInChild.find(_.firstParamTypeIsRef(StringClass)).get

    val polyInSuper = SuperMonoClass.getDecls(name"overloadedPoly").map(_.asTerm)
    val poly1InSuper = polyInSuper.find(_.typeParamCountIs(1)).get
    val poly2InSuper = polyInSuper.find(_.typeParamCountIs(2)).get

    val polyInChild = ChildMonoClass.getDecls(name"overloadedPoly").map(_.asTerm)
    val poly1InChild = polyInChild.find(_.typeParamCountIs(1)).get
    val poly2InChild = polyInChild.find(_.typeParamCountIs(2)).get

    // From intInSuper

    assert(clue(intInSuper.overriddenSymbol(SuperMonoClass)) == Some(intInSuper))
    assert(clue(intInSuper.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(intInSuper.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(intInSuper.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(intInSuper.overridingSymbol(SuperMonoClass)) == Some(intInSuper))
    assert(clue(intInSuper.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(intInSuper.overridingSymbol(MidMonoClass)) == None)
    assert(clue(intInSuper.overridingSymbol(ChildMonoClass)) == Some(intInChild))

    assert(clue(intInSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(intInSuper.nextOverriddenSymbol) == None)

    // From intInChild

    assert(clue(intInChild.overriddenSymbol(SuperMonoClass)) == Some(intInSuper))
    assert(clue(intInChild.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(intInChild.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(intInChild.overriddenSymbol(ChildMonoClass)) == Some(intInChild))

    assert(clue(intInChild.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(intInChild.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(intInChild.overridingSymbol(MidMonoClass)) == None)
    assert(clue(intInChild.overridingSymbol(ChildMonoClass)) == Some(intInChild))

    assert(clue(intInChild.allOverriddenSymbols.toList) == List(intInSuper))
    assert(clue(intInChild.nextOverriddenSymbol) == Some(intInSuper))

    // From stringInSuper

    assert(clue(stringInSuper.overriddenSymbol(SuperMonoClass)) == Some(stringInSuper))
    assert(clue(stringInSuper.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(stringInSuper.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(stringInSuper.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(stringInSuper.overridingSymbol(SuperMonoClass)) == Some(stringInSuper))
    assert(clue(stringInSuper.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(stringInSuper.overridingSymbol(MidMonoClass)) == None)
    assert(clue(stringInSuper.overridingSymbol(ChildMonoClass)) == Some(stringInChild))

    assert(clue(stringInSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(stringInSuper.nextOverriddenSymbol) == None)

    // From stringInChild

    assert(clue(stringInChild.overriddenSymbol(SuperMonoClass)) == Some(stringInSuper))
    assert(clue(stringInChild.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(stringInChild.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(stringInChild.overriddenSymbol(ChildMonoClass)) == Some(stringInChild))

    assert(clue(stringInChild.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(stringInChild.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(stringInChild.overridingSymbol(MidMonoClass)) == None)
    assert(clue(stringInChild.overridingSymbol(ChildMonoClass)) == Some(stringInChild))

    assert(clue(stringInChild.allOverriddenSymbols.toList) == List(stringInSuper))
    assert(clue(stringInChild.nextOverriddenSymbol) == Some(stringInSuper))

    // From poly1InSuper

    assert(clue(poly1InSuper.overriddenSymbol(SuperMonoClass)) == Some(poly1InSuper))
    assert(clue(poly1InSuper.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly1InSuper.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(poly1InSuper.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(poly1InSuper.overridingSymbol(SuperMonoClass)) == Some(poly1InSuper))
    assert(clue(poly1InSuper.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly1InSuper.overridingSymbol(MidMonoClass)) == None)
    assert(clue(poly1InSuper.overridingSymbol(ChildMonoClass)) == Some(poly1InChild))

    assert(clue(poly1InSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(poly1InSuper.nextOverriddenSymbol) == None)

    // From poly1InChild

    assert(clue(poly1InChild.overriddenSymbol(SuperMonoClass)) == Some(poly1InSuper))
    assert(clue(poly1InChild.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly1InChild.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(poly1InChild.overriddenSymbol(ChildMonoClass)) == Some(poly1InChild))

    assert(clue(poly1InChild.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(poly1InChild.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly1InChild.overridingSymbol(MidMonoClass)) == None)
    assert(clue(poly1InChild.overridingSymbol(ChildMonoClass)) == Some(poly1InChild))

    assert(clue(poly1InChild.allOverriddenSymbols.toList) == List(poly1InSuper))
    assert(clue(poly1InChild.nextOverriddenSymbol) == Some(poly1InSuper))

    // From poly2InSuper

    assert(clue(poly2InSuper.overriddenSymbol(SuperMonoClass)) == Some(poly2InSuper))
    assert(clue(poly2InSuper.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly2InSuper.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(poly2InSuper.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(poly2InSuper.overridingSymbol(SuperMonoClass)) == Some(poly2InSuper))
    assert(clue(poly2InSuper.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly2InSuper.overridingSymbol(MidMonoClass)) == None)
    assert(clue(poly2InSuper.overridingSymbol(ChildMonoClass)) == Some(poly2InChild))

    assert(clue(poly2InSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(poly2InSuper.nextOverriddenSymbol) == None)

    // From poly2InChild

    assert(clue(poly2InChild.overriddenSymbol(SuperMonoClass)) == Some(poly2InSuper))
    assert(clue(poly2InChild.overriddenSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly2InChild.overriddenSymbol(MidMonoClass)) == None)
    assert(clue(poly2InChild.overriddenSymbol(ChildMonoClass)) == Some(poly2InChild))

    assert(clue(poly2InChild.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(poly2InChild.overridingSymbol(SuperMonoTraitClass)) == None)
    assert(clue(poly2InChild.overridingSymbol(MidMonoClass)) == None)
    assert(clue(poly2InChild.overridingSymbol(ChildMonoClass)) == Some(poly2InChild))

    assert(clue(poly2InChild.allOverriddenSymbols.toList) == List(poly2InSuper))
    assert(clue(poly2InChild.nextOverriddenSymbol) == Some(poly2InSuper))
  }

  testWithContext("overrides-cannot-override") {
    val OverridesPath = name"inheritance" / tname"Overrides" / obj
    val SuperMonoClass = resolve(OverridesPath / tname"SuperMono").asClass
    val ChildMonoClass = resolve(OverridesPath / tname"ChildMono").asClass

    val superCtor = SuperMonoClass.getDecl(nme.Constructor).get.asTerm
    val childCtor = ChildMonoClass.getDecl(nme.Constructor).get.asTerm

    val superPrivate = SuperMonoClass.getDecl(name"privateMethod").get.asTerm
    val childPrivate = ChildMonoClass.getDecl(name"privateMethod").get.asTerm

    // From superCtor

    assert(clue(superCtor.overriddenSymbol(SuperMonoClass)) == Some(superCtor))
    assert(clue(superCtor.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(superCtor.overridingSymbol(SuperMonoClass)) == Some(superCtor))
    assert(clue(superCtor.overridingSymbol(ChildMonoClass)) == None)

    assert(clue(superCtor.allOverriddenSymbols.toList) == Nil)
    assert(clue(superCtor.nextOverriddenSymbol) == None)

    // From childCtor

    assert(clue(childCtor.overriddenSymbol(SuperMonoClass)) == None)
    assert(clue(childCtor.overriddenSymbol(ChildMonoClass)) == Some(childCtor))

    assert(clue(childCtor.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(childCtor.overridingSymbol(ChildMonoClass)) == Some(childCtor))

    assert(clue(childCtor.allOverriddenSymbols.toList) == Nil)
    assert(clue(childCtor.nextOverriddenSymbol) == None)

    // From superPrivate

    assert(clue(superPrivate.overriddenSymbol(SuperMonoClass)) == Some(superPrivate))
    assert(clue(superPrivate.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(superPrivate.overridingSymbol(SuperMonoClass)) == Some(superPrivate))
    assert(clue(superPrivate.overridingSymbol(ChildMonoClass)) == None)

    assert(clue(superPrivate.allOverriddenSymbols.toList) == Nil)
    assert(clue(superPrivate.nextOverriddenSymbol) == None)

    // From childPrivate

    assert(clue(childPrivate.overriddenSymbol(SuperMonoClass)) == None)
    assert(clue(childPrivate.overriddenSymbol(ChildMonoClass)) == Some(childPrivate))

    assert(clue(childPrivate.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(childPrivate.overridingSymbol(ChildMonoClass)) == Some(childPrivate))

    assert(clue(childPrivate.allOverriddenSymbols.toList) == Nil)
    assert(clue(childPrivate.nextOverriddenSymbol) == None)
  }

  testWithContext("overrides-poly") {
    val OverridesPath = name"inheritance" / tname"Overrides" / obj
    val SuperPolyClass = resolve(OverridesPath / tname"SuperPoly").asClass
    val ChildPolyClass = resolve(OverridesPath / tname"ChildPoly").asClass

    val List(superPolyA, superPolyB) = SuperPolyClass.typeParams: @unchecked
    val List(childPolyX) = ChildPolyClass.typeParams: @unchecked

    val IntClass = defn.IntClass

    extension (meth: TermSymbol)
      def firstParamTypeIsRef(cls: Symbol): Boolean =
        meth.declaredType.asInstanceOf[MethodType].paramTypes.head.isRef(cls)

    val fooInSuper = SuperPolyClass.getDecls(name"foo").map(_.asTerm)
    val fooAInSuper = fooInSuper.find(_.firstParamTypeIsRef(superPolyA)).get
    val fooBInSuper = fooInSuper.find(_.firstParamTypeIsRef(superPolyB)).get

    val fooInChild = ChildPolyClass.getDecls(name"foo").map(_.asTerm)
    val fooXInChild = fooInChild.find(_.firstParamTypeIsRef(childPolyX)).get
    val fooIntInChild = fooInChild.find(_.firstParamTypeIsRef(IntClass)).get

    // From fooAInSuper

    assert(clue(fooAInSuper.overriddenSymbol(SuperPolyClass)) == Some(fooAInSuper))
    assert(clue(fooAInSuper.overriddenSymbol(ChildPolyClass)) == None)

    assert(clue(fooAInSuper.overridingSymbol(SuperPolyClass)) == Some(fooAInSuper))
    assert(clue(fooAInSuper.overridingSymbol(ChildPolyClass)) == Some(fooXInChild))

    assert(clue(fooAInSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(fooAInSuper.nextOverriddenSymbol) == None)

    // From fooXInChild

    assert(clue(fooXInChild.overriddenSymbol(SuperPolyClass)) == Some(fooAInSuper))
    assert(clue(fooXInChild.overriddenSymbol(ChildPolyClass)) == Some(fooXInChild))

    assert(clue(fooXInChild.overridingSymbol(SuperPolyClass)) == None)
    assert(clue(fooXInChild.overridingSymbol(ChildPolyClass)) == Some(fooXInChild))

    assert(clue(fooXInChild.allOverriddenSymbols.toList) == List(fooAInSuper))
    assert(clue(fooXInChild.nextOverriddenSymbol) == Some(fooAInSuper))

    // From fooBInSuper

    assert(clue(fooBInSuper.overriddenSymbol(SuperPolyClass)) == Some(fooBInSuper))
    assert(clue(fooBInSuper.overriddenSymbol(ChildPolyClass)) == None)

    assert(clue(fooBInSuper.overridingSymbol(SuperPolyClass)) == Some(fooBInSuper))
    assert(clue(fooBInSuper.overridingSymbol(ChildPolyClass)) == Some(fooIntInChild))

    assert(clue(fooBInSuper.allOverriddenSymbols.toList) == Nil)
    assert(clue(fooBInSuper.nextOverriddenSymbol) == None)

    // From fooIntInChild

    assert(clue(fooIntInChild.overriddenSymbol(SuperPolyClass)) == Some(fooBInSuper))
    assert(clue(fooIntInChild.overriddenSymbol(ChildPolyClass)) == Some(fooIntInChild))

    assert(clue(fooIntInChild.overridingSymbol(SuperPolyClass)) == None)
    assert(clue(fooIntInChild.overridingSymbol(ChildPolyClass)) == Some(fooIntInChild))

    assert(clue(fooIntInChild.allOverriddenSymbols.toList) == List(fooBInSuper))
    assert(clue(fooIntInChild.nextOverriddenSymbol) == Some(fooBInSuper))
  }

  testWithContext("overrides-relaxed") {
    val OverridesPath = name"inheritance" / tname"Overrides" / obj
    val SuperMonoClass = resolve(OverridesPath / tname"SuperMono").asClass
    val ChildMonoClass = resolve(OverridesPath / tname"ChildMono").asClass

    val objectToString = defn.ObjectClass.getDecl(name"toString").get.asTerm
    val superToString = SuperMonoClass.getDecl(name"toString").get.asTerm
    val childToString = ChildMonoClass.getDecl(name"toString").get.asTerm

    // From objectToString

    assert(clue(objectToString.overriddenSymbol(defn.ObjectClass)) == Some(objectToString))
    assert(clue(objectToString.overriddenSymbol(SuperMonoClass)) == None)
    assert(clue(objectToString.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(objectToString.overridingSymbol(defn.ObjectClass)) == Some(objectToString))
    assert(clue(objectToString.overridingSymbol(SuperMonoClass)) == Some(superToString))
    assert(clue(objectToString.overridingSymbol(ChildMonoClass)) == Some(childToString))

    assert(clue(objectToString.allOverriddenSymbols.toList) == Nil)
    assert(clue(objectToString.nextOverriddenSymbol) == None)

    // From superToString

    assert(clue(superToString.overriddenSymbol(defn.ObjectClass)) == Some(objectToString))
    assert(clue(superToString.overriddenSymbol(SuperMonoClass)) == Some(superToString))
    assert(clue(superToString.overriddenSymbol(ChildMonoClass)) == None)

    assert(clue(superToString.overridingSymbol(defn.ObjectClass)) == None)
    assert(clue(superToString.overridingSymbol(SuperMonoClass)) == Some(superToString))
    assert(clue(superToString.overridingSymbol(ChildMonoClass)) == Some(childToString))

    assert(clue(superToString.allOverriddenSymbols.toList) == List(objectToString))
    assert(clue(superToString.nextOverriddenSymbol) == Some(objectToString))

    // From childToString

    assert(clue(childToString.overriddenSymbol(defn.ObjectClass)) == Some(objectToString))
    assert(clue(childToString.overriddenSymbol(SuperMonoClass)) == Some(superToString))
    assert(clue(childToString.overriddenSymbol(ChildMonoClass)) == Some(childToString))

    assert(clue(childToString.overridingSymbol(defn.ObjectClass)) == None)
    assert(clue(childToString.overridingSymbol(SuperMonoClass)) == None)
    assert(clue(childToString.overridingSymbol(ChildMonoClass)) == Some(childToString))

    assert(clue(childToString.allOverriddenSymbols.toList) == List(superToString, objectToString))
    assert(clue(childToString.nextOverriddenSymbol) == Some(superToString))
  }
}
