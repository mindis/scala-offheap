package offheap
package internal
package macros

trait Common extends Definitions {
  import c.universe.{ weakTypeOf => wt, _ }
  import c.universe.definitions._
  import c.internal._, decorators._

  def abort(msg: String, at: Position = c.enclosingPosition): Nothing = c.abort(at, msg)

  def panic(msg: String = ""): Nothing = abort(s"panic: $msg")

  def unreachable = panic("unreachable")

  def debug[T](header: String)(f: => T): T = {
    val res = f
    println(s"$header = $res")
    res
  }

  def fresh(pre: String): TermName = TermName(c.freshName(pre))

  def freshVal(pre: String, tpe: Type, value: Tree): ValDef = {
    val name = fresh(pre)
    val sym = enclosingOwner.newTermSymbol(name).setInfo(tpe)
    val vd = valDef(sym, value)
    vd
  }

  /** Extension to default type unlifting that also handles
   *  literal constant types produced after typechecking of classOf.
   */
  implicit object UnliftType extends Unliftable[Type] {
    def unapply(t: Tree) = t match {
      case Literal(Constant(tpe: Type)) =>
        Some(tpe)
      case tt: TypeTree if tt.tpe != null =>
        Some(tt.tpe)
      case q"${m: RefTree}.classOf[${tpe: Type}]" if m.symbol == PredefModule =>
        Some(tpe)
      case _ =>
        None
    }
  }

  class ExtractAnnotation(annSym: Symbol) {
    def unapply(sym: Symbol): Option[List[Tree]] = {
      val trees = sym.annotations.collect {
        case ann if ann.tpe.typeSymbol == annSym => ann.tree
      }
      if (trees.isEmpty) None else Some(trees)
    }
  }
  object ExtractEnum               extends ExtractAnnotation(EnumClass)
  object ExtractData               extends ExtractAnnotation(DataClass)
  object ExtractParent             extends ExtractAnnotation(ParentClass)
  object ExtractPotentialChildren  extends ExtractAnnotation(PotentialChildrenClass)
  object ExtractClassTag           extends ExtractAnnotation(ClassTagClass)
  object ExtractClassTagRange      extends ExtractAnnotation(ClassTagRangeClass)
  object ExtractParentExtractor    extends ExtractAnnotation(ParentExtractorClass)
  object ExtractPrimaryExtractor   extends ExtractAnnotation(PrimaryExtractorClass)
  object ExtractUniversalExtractor extends ExtractAnnotation(UniversalExtractorClass)
  object ExtractField              extends ExtractAnnotation(FieldClass)

  final case class Tag(value: Tree, tpt: Tree)

  case class Field(name: String, after: Tree, tpe: Type,
                   annots: List[Tree], offset: Long) {
    lazy val isData    = annots.collect { case q"new $c" if c.symbol == EmbedClass => c }.nonEmpty
    lazy val size      = if (isData) sizeOfData(tpe) else sizeOf(tpe)
    lazy val alignment = if (isData) alignmentOfData(tpe) else alignmentOf(tpe)
  }
  object Field {
    implicit val lift: Liftable[Field] = Liftable { f =>
      q"""
        new $FieldClass(${f.name}, ${f.after}, $PredefModule.classOf[${f.tpe}],
                        new $AnnotsClass(..${f.annots}), (${f.offset}: $SizeTpe))
      """
    }
    implicit val unlift: Unliftable[Field] = Unliftable {
      case q"""
        new $cls(${name: String}, $after, ${tpe: Type},
                 $newAnnots, (${offset: Long}: $_))
        """
        if cls.symbol == FieldClass =>
        val annots = newAnnots match {
          case q"new $_(..$anns)" => anns
          case q"new $_"          => Nil
        }
        Field(name, after, tpe, annots, offset)
    }
  }

  final case class Clazz(sym: Symbol) {
    lazy val fields =
      sym.asType.toType.members.collect {
        case ExtractField(t :: Nil) =>
          val q"${f: Field}" = t
          f
      }.toList.sortBy(_.offset)
    lazy val parents =
      ExtractParent.unapply(sym).toList.flatten.map {
        case q"new $_(${tpe: Type})" => tpe
      }
    lazy val tag =
      ExtractClassTag.unapply(sym).map(_.head).map {
        case q"new $_($value: $tpt)" => Tag(value, tpt)
      }
    lazy val isData = ExtractData.unapply(sym).nonEmpty
    lazy val isEnum = ExtractEnum.unapply(sym).nonEmpty
    lazy val children: List[Clazz] =
      ExtractPotentialChildren.unapply(sym).map {
        case q"new $_(..${tpes: List[Type]})" :: Nil =>
          tpes.map(Clazz.unapply).flatten
      }.getOrElse(Nil)
    lazy val size: Long = {
      assertLayoutComplete(sym, s"$sym must be defined before it's used")
      if (isData) {
        val lastfield = fields.maxBy(_.offset)
        lastfield.offset + lastfield.size
      } else if (isEnum) {
        children.map(_.size).max
      } else unreachable
    }
    lazy val alignment: Long = {
      assertLayoutComplete(sym, s"$sym must be defined before it's used")
      if (isData) {
        fields.map(f => alignmentOf(f.tpe)).max
      } else if (isEnum) {
        children.map(_.alignment).max
      } else unreachable
    }
  }
  object Clazz {
    final case class Attachment(value: Boolean)
    final case class InLayout()
    final case class LayoutComplete()
    def is(tpe: Type): Boolean =
      is(tpe.widen.typeSymbol)
    def is(sym: Symbol): Boolean = {
      sym.attachments.get[Clazz.Attachment].map { _.value }.getOrElse {
        val value =
          ExtractData.unapply(sym).nonEmpty ||
          ExtractEnum.unapply(sym).nonEmpty
        sym.updateAttachment(Clazz.Attachment(value))
        value
      }
    }
    def unapply(tpe: Type): Option[Clazz] =
      unapply(tpe.widen.typeSymbol)
    def unapply(sym: Symbol): Option[Clazz] =
      if (is(sym)) Some(Clazz(sym))
      else None
  }

  object ArrayOf {
    def is(tpe: Type): Boolean =
      tpe.typeSymbol == ArrayClass
    def unapply(tpe: Type): Option[Type] =
      if (!is(tpe)) None
      else Some(paramTpe(tpe))
  }

  object TupleOf {
    def unapply(tpe: Type): Option[List[Type]] =
      if (tpe.typeSymbol == UnitClass) Some(Nil)
      else TupleClass.seq.find(_ == tpe.typeSymbol).map(sym => tpe.baseType(sym).typeArgs)
  }

  object Primitive {
    def unapply(tpe: Type): Boolean = tpe.typeSymbol match {
      case sym: ClassSymbol if sym.isPrimitive && sym != UnitClass => true
      case _                                                       => false
    }
  }

  object Allocatable {
    def unapply(tpe: Type): Boolean = tpe match {
      case Primitive() | Clazz(_) => true
      case _                      => false
    }
  }

  def sizeOf(tpe: Type): Long = tpe match {
    case ByteTpe  | BooleanTpe => 1
    case ShortTpe | CharTpe    => 2
    case IntTpe   | FloatTpe   => 4
    case LongTpe  | DoubleTpe  => 8
    case _ if Clazz.is(tpe)    ||
              ArrayOf.is(tpe)  => 8
    case _                     => abort(s"can't compute size of $tpe")
  }

  def sizeOfData(tpe: Type): Long = tpe match {
    case Clazz(clazz) => clazz.size
    case _            => abort(s"$tpe is not a an offheap class")
  }

  def alignmentOf(tpe: Type) = tpe match {
    case ByteTpe  | BooleanTpe => 1
    case ShortTpe | CharTpe    => 2
    case IntTpe   | FloatTpe   => 4
    case LongTpe  | DoubleTpe  => 8
    case _ if Clazz.is(tpe)    ||
              ArrayOf.is(tpe)  => 8
    case _                     => abort(s"can't comput alignment for $tpe")
  }

  def alignmentOfData(tpe: Type) = tpe match {
    case Clazz(clazz) => clazz.alignment
    case _            => abort(s"$tpe is not an offheap class")
  }

  def validate(addr: Tree) = q"$SanitizerModule.validate($addr)"

  def read(addr: Tree, tpe: Type): Tree = {
    val vaddr = validate(addr)
    tpe match {
      case ByteTpe | ShortTpe  | IntTpe | LongTpe | FloatTpe | DoubleTpe | CharTpe =>
        val getT = TermName(s"get$tpe")
        q"$UNSAFE.$getT($vaddr)"
      case BooleanTpe =>
        q"$UNSAFE.getByte($vaddr) != ${Literal(Constant(0.toByte))}"
      case ArrayOf(tpe) =>
        q"$ArrayModule.fromAddr[$tpe]($UNSAFE.getLong($vaddr))"
      case Clazz(_) =>
        val companion = tpe.typeSymbol.companion
        q"$companion.fromAddr($UNSAFE.getLong($vaddr))"
    }
  }

  def write(addr: Tree, tpe: Type, value: Tree): Tree = {
    val vaddr = validate(addr)
    tpe match {
      case ByteTpe | ShortTpe  | IntTpe | LongTpe | FloatTpe | DoubleTpe | CharTpe =>
        val putT = TermName(s"put$tpe")
        q"$UNSAFE.$putT($vaddr, $value)"
      case BooleanTpe =>
        q"""
          $UNSAFE.putByte($vaddr,
                          if ($value) ${Literal(Constant(1.toByte))}
                          else ${Literal(Constant(0.toByte))})
        """
      case ArrayOf(_) =>
        q"$UNSAFE.putLong($vaddr, $ArrayModule.toAddr($value))"
      case Clazz(_) =>
        val companion = tpe.typeSymbol.companion
        q"$UNSAFE.putLong($vaddr, $companion.toAddr($value))"
    }
  }

  // TODO: handle non-function literal cases
  def appSubs(f: Tree, argValue: Tree, subs: Tree => Tree) = f match {
    case q"($param => $body)" =>
      val q"$_ val $_: $argTpt = $_" = param
      changeOwner(body, f.symbol, enclosingOwner)
      val (arg, argDef) = argValue match {
        case refTree: RefTree
          if refTree.symbol.isTerm
          && refTree.symbol.asTerm.isStable =>
          (refTree, q"")
        case _ =>
          val vd = freshVal("arg", argTpt.tpe, argValue)
          (q"${vd.symbol}", vd)
      }
      val transformedBody = typingTransform(body) { (tree, api) =>
        tree match {
          case id: Ident if id.symbol == param.symbol =>
            api.typecheck(subs(q"$arg"))
          case _ =>
            api.default(tree)
        }
      }
      q"..$argDef; $transformedBody"
    case _             =>
      q"$f($argValue)"
  }

  def app(f: Tree, argValue: Tree) =
    appSubs(f, argValue, identity)

  def stabilized(tree: Tree)(f: Tree => Tree) = tree match {
    case q"${refTree: RefTree}"
      if refTree.symbol.isTerm
      && refTree.symbol.asTerm.isStable =>
      f(refTree)
    case _ =>
      if (tree.tpe == null) {
        val stable = fresh("stable")
        q"val $stable = $tree; ${f(q"$stable")}"
      } else {
        val stable = freshVal("stable", tree.tpe, tree)
        val fapp = f(q"${stable.symbol}")
        q"$stable; $fapp"
      }
  }

  def paramTpe(tpe: Type): Type = tpe.typeArgs.head
  def paramTpe(t: Tree): Type   = paramTpe(t.tpe)

  def assertAllocatable(T: Type, msg: String = ""): Unit =
    T match {
      case Allocatable() => ()
      case _             => abort(if (msg.isEmpty) s"$T is not allocatable" else msg)
    }

  def assertNotInLayout(sym: Symbol, msg: String) =
    if (sym.attachments.get[Clazz.InLayout].nonEmpty)
      abort(msg)

  def assertLayoutComplete(sym: Symbol, msg: String) =
    if (sym.attachments.get[Clazz.LayoutComplete].isEmpty) {
      abort(msg)
    }

  def isEnum(T: Type): Boolean = ExtractEnum.unapply(T.typeSymbol).nonEmpty

  def isData(T: Type): Boolean = ExtractData.unapply(T.typeSymbol).nonEmpty

  def isRelated(T: Type, C: Type): Boolean = {
    def topmostParent(sym: Symbol): Symbol =
      ExtractParent.unapply(sym).map {
        case _ :+ q"new $_(${tpe: Type})" => tpe.typeSymbol
      }.getOrElse(sym)
    topmostParent(T.typeSymbol) == topmostParent(C.typeSymbol)
  }

  def isParent(T: Type, C: Type): Boolean =
    ExtractParent.unapply(C.typeSymbol).getOrElse(Nil).exists {
      case q"new $_(${tpe: Type})" => tpe.typeSymbol == T.typeSymbol
      case _                       => false
    }

  def cast(v: Tree, from: Type, to: Type) = {
    val fromCompanion = from.typeSymbol.companion
    val toCompanion = to.typeSymbol.companion
    q"$toCompanion.fromAddr($fromCompanion.toAddr($v))"
  }

  def isNull(addr: Tree)  = q"$addr == 0L"
  def notNull(addr: Tree) = q"$addr != 0L"

  def classOf(tpt: Tree) = q"$PredefModule.classOf[$tpt]"
}
