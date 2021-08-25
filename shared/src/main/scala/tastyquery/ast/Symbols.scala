package tastyquery.ast

import scala.collection.mutable
import tastyquery.ast.Names._

import dotty.tools.tasty.TastyFormat.NameTags

object Symbols {
  val NoSymbol = new RegularSymbol(Names.EmptyTermName, null)

  abstract class Symbol private[Symbols] (val name: Name, val owner: Symbol) {
    override def toString: String = s"symbol[$name]"
  }

  object Symbol {
    def unapply(s: Symbol): Option[Name] = Some(s.name)
  }

  final class RegularSymbol(override val name: Name, override val owner: Symbol) extends Symbol(name, owner)

  abstract class DeclaringSymbol(override val name: Name, override val owner: DeclaringSymbol)
      extends Symbol(name, owner) {
    if (owner != null) {
      // Declaring symbol is always a declaration in its owner
      owner.addDecl(this)
    } else {
      // Root package is the only symbol that is allowed to not have an owner
      assert(name == RootName)
    }
    /* A map from the name of a declaration directly inside this symbol to the corresponding symbol
     * The qualifiers on the name are not dropped. For instance, the package names are always fully qualified. */
    protected val declarations: mutable.HashMap[Name, Symbol] = mutable.HashMap[Name, Symbol]()

    def addDecl(decl: Symbol): Unit         = declarations(decl.name) = decl
    def getDecl(name: Name): Option[Symbol] = declarations.get(name)
  }

  class MethodSymbol(override val name: Name, override val owner: DeclaringSymbol) extends DeclaringSymbol(name, owner)

  class ClassSymbol(override val name: Name, override val owner: DeclaringSymbol) extends DeclaringSymbol(name, owner)

  // TODO: typename or term name?
  class PackageClassSymbol(override val name: Name, override val owner: DeclaringSymbol)
      extends ClassSymbol(name, owner) {
    def findPackageSymbol(packageName: TermName): Option[PackageClassSymbol] = packageName match {
      case _: SimpleName => getPackageDecl(packageName)
      case QualifiedName(NameTags.QUALIFIED, prefix, suffix) =>
        if (prefix == name)
          getPackageDecl(packageName)
        else
          // recurse
          findPackageSymbol(prefix).flatMap(_.findPackageSymbol(packageName))
      case _ => throw IllegalArgumentException(s"Unexpected package name: $name")
    }

    private def getPackageDecl(packageName: TermName): Option[PackageClassSymbol] =
      getDecl(packageName).map(_.asInstanceOf[PackageClassSymbol])
  }

  abstract class SymbolFactory[T <: Symbol] {
    def createSymbol(name: Name, owner: DeclaringSymbol): T
  }

  object RegularSymbolFactory extends SymbolFactory[RegularSymbol] {
    override def createSymbol(name: Name, owner: DeclaringSymbol): RegularSymbol = new RegularSymbol(name, owner)
  }

  object ClassSymbolFactory extends SymbolFactory[ClassSymbol] {
    override def createSymbol(name: Name, owner: DeclaringSymbol): ClassSymbol = new ClassSymbol(name, owner)
  }

  object PackageClassSymbolFactory extends SymbolFactory[PackageClassSymbol] {
    override def createSymbol(name: Name, owner: DeclaringSymbol): PackageClassSymbol =
      new PackageClassSymbol(name, owner)
  }

  object MethodSymbolFactory extends SymbolFactory[MethodSymbol] {
    override def createSymbol(name: Name, owner: DeclaringSymbol): MethodSymbol = new MethodSymbol(name, owner)
  }
}
