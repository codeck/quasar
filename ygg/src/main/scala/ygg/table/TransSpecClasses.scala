/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ygg.table

import ygg._, common._, json._
import scala.{ Symbol => Sym }

package object trans {
  type TransSpec1 = TransSpec[Source1]
  type TransSpec2 = TransSpec[Source2]
  // type UnarySpec  = TransSpec[Source1]
  // type BinarySpec = TransSpec[Source2]

  val ID   = new KVTransSpecBuilder[Source1](Leaf(Source))
  val ID_L = new KVTransSpecBuilder[Source2](Leaf(SourceLeft))
  val ID_R = new KVTransSpecBuilder[Source2](Leaf(SourceRight))

  def where(name: String): WhereOps1 = new WhereOps1(CPathField(name))

  class WhereOps1(field: CPathField) {
    def is(value: CValue) = Filter(ID \ field, EqualLiteral(ID, value, invert = false))
  }

  def wrapOuterConcat[A](xs: (String -> TransSpec[A])*): OuterObjectConcat[A] =
    OuterObjectConcat(xs map (kv => kv._2 as kv._1): _*)

  def filterObject(names: String*) =
    OuterObjectConcat(names map (ID \ _): _*)

  implicit class TransSymOps(private val self: Sym) {
    def as(as: String): TransSpec1 = ID \ self.name as as
    def at(idx: Int): TransSpec1   = ID \ self.name at idx

    def <<(): TransSpec2 = ID_L \ self.name
    def >>(): TransSpec2 = ID_R \ self.name

    def <<(as: String): TransSpec2 = <<() as as
    def >>(as: String): TransSpec2 = >>() as as
  }

  implicit def liftSelect1(x: Sym): TransSpec1        = ID \ x.name

  implicit def transSpecBuilder[A](x: TransSpec[A]): TransSpecBuilder[A]        = new TransSpecBuilder(x)
  implicit def transSpecBuilderResult[A](x: TransSpecBuilder[A]): TransSpec[A]  = x.spec
  implicit def transSpecOps[A](x: TransSpec[A]): TransSpecOps[A]                = new TransSpecOps(x)
  implicit def liftCValue[A](a: A)(implicit C: CValueType[A]): CWrappedValue[A] = C(a)

  val root = ID

  sealed trait Selector {
    def run: TransSpec1 = ID \ this
    def toVector: Vector[Selector.Atom] = this match {
      case x: Selector.Atom   => Vector(x)
      case Selector.Multi(xs) => xs flatMap (_.toVector)
    }
  }
  object Selector {
    sealed trait Atom extends Selector
    final case class Index(index: Int)             extends Atom
    final case class Field(name: String)           extends Atom
    // final case class Var(sym: Sym)                 extends Atom
    final case class Path(cpath: CPath)            extends Atom
    final case class Multi(sels: Vector[Selector]) extends Selector

    def apply(sels: Selector*): Selector = new Multi(sels.toVector)
  }

  implicit def liftIndex(index: Int): Selector   = Selector.Index(index)
  implicit def liftField(name: String): Selector = Selector.Field(name)
  implicit def liftFieldSym(sym: Sym): Selector  = Selector.Field(sym.name)
  // implicit def liftVar(sym: Sym): Selector    = Selector.Var(sym)
  implicit def liftPath(cpath: CPath): Selector  = Selector.Path(cpath)
}

package trans {
  sealed trait SourceType       extends Product with Serializable
  sealed trait Source1          extends SourceType
  sealed trait Source2          extends SourceType
  final case object Source      extends Source1
  final case object SourceLeft  extends Source2
  final case object SourceRight extends Source2

  class KVTransSpecBuilder[A](spec: TransSpec[A]) extends TransSpecBuilder[A](spec) {
    def emptyArray() = ConstLiteral(CEmptyArray, spec)

    def a      = spec \ "a"
    def b      = spec \ "b"
    def bar    = spec \ "bar"
    def c      = spec \ "c"
    def field  = spec \ "field"
    def foo    = spec \ "foo"
    def id     = spec \ "id"
    def key    = spec \ "key"
    def ref    = spec \ "ref"
    def value  = spec \ "value"
    def value1 = spec \ "value1"
    def value2 = spec \ "value2"
  }
  class TransSpecOps[A](spec: TransSpec[A]) {
    def mapSources[B](f: A => B): TransSpec[B] = TransSpec.mapSources(spec)(f)
  }
  class TransSpecDynamic[A](spec: TransSpec[A]) extends Dynamic {
    def selectDynamic(name: String) = spec \ name
  }
  class TransSpecBuilder[A](val spec: TransSpec[A]) {
    type This    = TransSpec[A]
    type Builder = TransSpecBuilder[A]

    def dyn: TransSpecDynamic[A] = new TransSpecDynamic[A](spec)

    protected def next[A](x: This): Builder = new TransSpecBuilder(x)

    def mapLeaves(f: A => This)         = deepMap({ case Leaf(x) => f(x) })
    def deepMap(pf: MaybeSelf[This])    = TransSpec.deepMap(spec)(pf)
    def deepMap1(fn: CF1)               = DeepMap1(spec, fn)
    def deepEquals(that: This)          = Equal(spec, that)
    def isEqual(that: CValue)           = EqualLiteral(spec, right = that, invert = false)
    def delete(fields: CPathField*)     = ObjectDelete(spec, fields.toSet)
    def filter(p: This)                 = Filter(spec, p)
    def isType(tp: JType)               = IsType(spec, tp)
    def map1(fn: CF1)                   = Map1(spec, fn)
    def scan(scanner: Scanner)          = Scan(spec, scanner)
    def select(field: CPathField): This = this \ field.name
    def select(name: String): This      = this \ name

    def at(idx: Int)     = WrapArray(this \ idx)
    def as(name: String) = WrapObject(spec, name)
    def asArray()        = WrapArray(spec)

    def \(sel: Selector): This = sel.toVector.foldLeft(spec) {
      case (spec, Selector.Index(index)) => DerefArrayStatic(spec, index)
      case (spec, Selector.Field(name))  => DerefObjectStatic(spec, name)
      // case (spec, Selector.Var(sym))     => ???
      case (spec, Selector.Path(cpath))  => cpath.nodes.foldLeft(spec)(_ \ _)
    }

    def fromLeft  = spec mapSources (_ => SourceLeft)
    def fromRight = spec mapSources (_ => SourceRight)

    def apply(index: Int): This = this \ index

    def @:(meta: String): This = this \ CPathMeta(meta)
    def \(index: Int): This    = this \ CPathIndex(index)
    def \(name: String): This  = this \ CPath(name)
    def \(path: CPath): This   = path.nodes.foldLeft(spec)(_ \ _)

    def \(node: CPathNode): This = node match {
      case x: CPathField => DerefObjectStatic(spec, x)
      case x: CPathIndex => DerefArrayStatic(spec, x)
      case x: CPathMeta  => DerefMetadataStatic(spec, x)
      case CPathArray    => InnerArrayConcat(spec)
    }
  }

  sealed trait TransSpec[+A]  extends AnyRef
  sealed trait ObjectSpec[+A] extends TransSpec[A]
  sealed trait ArraySpec[+A]  extends TransSpec[A]

  object Filter {
    def apply[A](spec: TransSpec[A]): Filter[A] = Filter(spec, spec)
  }

  /** Done (according to the existing comments)
   *
   *  Scan: Adds a column to the output in the manner of scanLeft
   *  Map2: apply a function to the cartesian product of the transformed left and right subsets of columns
   *  InnerObjectConcat: Perform the specified transformation on the all sources, and then create a new set of columns containing all the resulting columns.
   *  WrapObject: Take the output of the specified TransSpec and prefix all of the resulting selectors with the specified field.
   *  Typed: Filter out all the source columns whose selector and CType are not specified by the supplied JType
   *  TypedSubsumes: Filter out all the source columns whose selector and CType are not specified by the supplied JType.
   *     If the set of columns does not cover the JType specified, this will return the empty slice.
   *  IsType: return a Boolean column. returns true for a given row when all of the columns specified by the supplied JType are defined
   */
  final case class DeepMap1[+A](source: TransSpec[A], f: CF1)                      extends TransSpec[A]
  final case class DerefArrayDynamic[+A](left: TransSpec[A], right: TransSpec[A])  extends TransSpec[A]
  final case class DerefArrayStatic[+A](source: TransSpec[A], element: CPathIndex) extends TransSpec[A]
  final case class DerefObjectDynamic[+A](left: TransSpec[A], right: TransSpec[A]) extends TransSpec[A]
  final case class DerefObjectStatic[+A](source: TransSpec[A], field: CPathField)  extends TransSpec[A]
  final case class Equal[+A](left: TransSpec[A], right: TransSpec[A])              extends TransSpec[A]
  final case class Filter[+A](source: TransSpec[A], predicate: TransSpec[A])       extends TransSpec[A]
  final case class IsType[+A](source: TransSpec[A], tpe: JType)                    extends TransSpec[A]
  final case class Leaf[+A](source: A)                                             extends TransSpec[A]
  final case class Map1[+A](source: TransSpec[A], f: CF1)                          extends TransSpec[A]
  final case class Map2[+A](left: TransSpec[A], right: TransSpec[A], f: CF2)       extends TransSpec[A]
  final case class Scan[+A](source: TransSpec[A], scanner: Scanner)                extends TransSpec[A]
  final case class TypedSubsumes[+A](source: TransSpec[A], tpe: JType)             extends TransSpec[A]
  final case class Typed[+A](source: TransSpec[A], tpe: JType)                     extends TransSpec[A]

  final case class InnerArrayConcat[+A](arrays: TransSpec[A]*)                     extends ArraySpec[A]
  final case class OuterArrayConcat[+A](arrays: TransSpec[A]*)                     extends ArraySpec[A]
  final case class WrapArray[+A](source: TransSpec[A])                             extends ArraySpec[A]

  final case class InnerObjectConcat[+A](objects: TransSpec[A]*)                   extends ObjectSpec[A]
  final case class OuterObjectConcat[+A](objects: TransSpec[A]*)                   extends ObjectSpec[A]
  final case class WrapObject[+A](source: TransSpec[A], field: String)             extends ObjectSpec[A]

  /** Not marked as done, but probably done. */

  // The result table has constant value wherever a row in Target has at least one defined member.
  final case class ConstLiteral[+A](value: CValue, target: TransSpec[A]) extends TransSpec[A]

  /** Not marked as done. */
  final case class ArraySwap[+A](source: TransSpec[A], index: Int)                                             extends TransSpec[A]
  final case class Cond[+A](pred: TransSpec[A], left: TransSpec[A], right: TransSpec[A])                       extends TransSpec[A]
  final case class DerefMetadataStatic[+A](source: TransSpec[A], field: CPathMeta)                             extends TransSpec[A]
  final case class EqualLiteral[+A](left: TransSpec[A], right: CValue, invert: Boolean)                        extends TransSpec[A]
  final case class FilterDefined[+A](source: TransSpec[A], definedFor: TransSpec[A], definedness: Definedness) extends TransSpec[A]
  final case class ObjectDelete[+A](source: TransSpec[A], fields: Set[CPathField])                             extends TransSpec[A]
  final case class WrapObjectDynamic[+A](left: TransSpec[A], right: TransSpec[A])                              extends TransSpec[A]

  object TransSpec {
    import CPath._

    def concatChildren[A](tree: CPathTree[Int], leaf: TransSpec[A]): TransSpec[A] = {
      def createSpec(tree: CPathTree[Int]): TransSpec[A] = tree match {
        case node @ RootNode(seq)                  => concatChildren(node, leaf)
        case node @ FieldNode(CPathField(name), _) => concatChildren(node, leaf) as name
        case node @ IndexNode(CPathIndex(_), _)    => concatChildren(node, leaf).asArray() //assuming that indices received in order
        case LeafNode(idx)                         => leaf \ idx
      }
      val initialSpecs = tree match {
        case RootNode(children)     => children map createSpec
        case FieldNode(_, children) => children map createSpec
        case IndexNode(_, children) => children map createSpec
        case LeafNode(_)            => Seq()
      }

      val result = initialSpecs reduceOption { (t1, t2) =>
        (t1, t2) match {
          case (t1: ObjectSpec[_], t2: ObjectSpec[_]) => trans.InnerObjectConcat(t1, t2)
          case (t1: ArraySpec[_], t2: ArraySpec[_])   => trans.InnerArrayConcat(t1, t2)
          case _                                      => abort("cannot have this")
        }
      }

      result getOrElse leaf
    }

    def mapSources[A, B](spec: TransSpec[A])(f: A => B): TransSpec[B] = spec match {
      case Leaf(source)                                   => Leaf(f(source))
      case ConstLiteral(value, target)                    => ConstLiteral(value, mapSources(target)(f))
      case Filter(source, pred)                           => Filter(mapSources(source)(f), mapSources(pred)(f))
      case FilterDefined(source, definedFor, definedness) => FilterDefined(mapSources(source)(f), mapSources(definedFor)(f), definedness)
      case Scan(source, scanner)                          => Scan(mapSources(source)(f), scanner)
      case Map1(source, f1)                               => Map1(mapSources(source)(f), f1)
      case DeepMap1(source, f1)                           => DeepMap1(mapSources(source)(f), f1)
      case Map2(left, right, f2)                          => Map2(mapSources(left)(f), mapSources(right)(f), f2)
      case OuterObjectConcat(objects @ _ *)               => OuterObjectConcat(objects.map(mapSources(_)(f)): _*)
      case InnerObjectConcat(objects @ _ *)               => InnerObjectConcat(objects.map(mapSources(_)(f)): _*)
      case ObjectDelete(source, fields)                   => ObjectDelete(mapSources(source)(f), fields)
      case InnerArrayConcat(arrays @ _ *)                 => InnerArrayConcat(arrays.map(mapSources(_)(f)): _*)
      case OuterArrayConcat(arrays @ _ *)                 => OuterArrayConcat(arrays.map(mapSources(_)(f)): _*)
      case WrapObject(source, field)                      => WrapObject(mapSources(source)(f), field)
      case WrapObjectDynamic(left, right)                 => WrapObjectDynamic(mapSources(left)(f), mapSources(right)(f))
      case WrapArray(source)                              => WrapArray(mapSources(source)(f))
      case DerefMetadataStatic(source, field)             => DerefMetadataStatic(mapSources(source)(f), field)
      case DerefObjectStatic(source, field)               => DerefObjectStatic(mapSources(source)(f), field)
      case DerefObjectDynamic(left, right)                => DerefObjectDynamic(mapSources(left)(f), mapSources(right)(f))
      case DerefArrayStatic(source, element)              => DerefArrayStatic(mapSources(source)(f), element)
      case DerefArrayDynamic(left, right)                 => DerefArrayDynamic(mapSources(left)(f), mapSources(right)(f))
      case ArraySwap(source, index)                       => ArraySwap(mapSources(source)(f), index)
      case Typed(source, tpe)                             => Typed(mapSources(source)(f), tpe)
      case TypedSubsumes(source, tpe)                     => TypedSubsumes(mapSources(source)(f), tpe)
      case IsType(source, tpe)                            => IsType(mapSources(source)(f), tpe)
      case Equal(left, right)                             => Equal(mapSources(left)(f), mapSources(right)(f))
      case EqualLiteral(source, value, invert)            => EqualLiteral(mapSources(source)(f), value, invert)
      case Cond(pred, left, right)                        => Cond(mapSources(pred)(f), mapSources(left)(f), mapSources(right)(f))
    }

    def deepMap[A](spec: TransSpec[A])(f: MaybeSelf[TransSpec[A]]): TransSpec[A] = spec match {
      case x if f isDefinedAt x => f(x)

      case x @ Leaf(source)                  => x
      case trans.ConstLiteral(value, target) => trans.ConstLiteral(value, deepMap(target)(f))

      case trans.Filter(source, pred) => trans.Filter(deepMap(source)(f), deepMap(pred)(f))
      case trans.FilterDefined(source, definedFor, definedness) =>
        trans.FilterDefined(deepMap(source)(f), deepMap(definedFor)(f), definedness)

      case Scan(source, scanner) => Scan(deepMap(source)(f), scanner)
      // case MapWith(source, mapper) => MapWith(deepMap(source)(f), mapper)

      case trans.Map1(source, f1)      => trans.Map1(deepMap(source)(f), f1)
      case trans.DeepMap1(source, f1)  => trans.DeepMap1(deepMap(source)(f), f1)
      case trans.Map2(left, right, f2) => trans.Map2(deepMap(left)(f), deepMap(right)(f), f2)

      case trans.OuterObjectConcat(objects @ _ *) => trans.OuterObjectConcat(objects.map(deepMap(_)(f)): _*)
      case trans.InnerObjectConcat(objects @ _ *) => trans.InnerObjectConcat(objects.map(deepMap(_)(f)): _*)
      case trans.ObjectDelete(source, fields)     => trans.ObjectDelete(deepMap(source)(f), fields)
      case trans.InnerArrayConcat(arrays @ _ *)   => trans.InnerArrayConcat(arrays.map(deepMap(_)(f)): _*)
      case trans.OuterArrayConcat(arrays @ _ *)   => trans.OuterArrayConcat(arrays.map(deepMap(_)(f)): _*)

      case trans.WrapObject(source, field)        => trans.WrapObject(deepMap(source)(f), field)
      case trans.WrapObjectDynamic(source, right) => trans.WrapObjectDynamic(deepMap(source)(f), deepMap(right)(f))
      case trans.WrapArray(source)                => trans.WrapArray(deepMap(source)(f))

      case DerefMetadataStatic(source, field) => DerefMetadataStatic(deepMap(source)(f), field)

      case DerefObjectStatic(source, field)  => deepMap(source)(f) select field
      case DerefObjectDynamic(left, right)   => DerefObjectDynamic(deepMap(left)(f), deepMap(right)(f))
      case DerefArrayStatic(source, element) => DerefArrayStatic(deepMap(source)(f), element)
      case DerefArrayDynamic(left, right)    => DerefArrayDynamic(deepMap(left)(f), deepMap(right)(f))

      case trans.ArraySwap(source, index) => trans.ArraySwap(deepMap(source)(f), index)

      case Typed(source, tpe)         => Typed(deepMap(source)(f), tpe)
      case TypedSubsumes(source, tpe) => TypedSubsumes(deepMap(source)(f), tpe)
      case IsType(source, tpe)        => IsType(deepMap(source)(f), tpe)

      case trans.Equal(left, right)                  => trans.Equal(deepMap(left)(f), deepMap(right)(f))
      case trans.EqualLiteral(source, value, invert) => trans.EqualLiteral(deepMap(source)(f), value, invert)

      case trans.Cond(pred, left, right) => trans.Cond(deepMap(pred)(f), deepMap(left)(f), deepMap(right)(f))
    }
  }

  object TransSpec1 {
    val Id = root.spec
  }

  sealed trait GroupKeySpec {
    def &&(rhs: GroupKeySpec): GroupKeySpec = GroupKeySpecAnd(this, rhs)
    def ||(rhs: GroupKeySpec): GroupKeySpec = GroupKeySpecOr(this, rhs)
  }

  /**
    * Definition for a single (non-composite) key part.
    *
    * @param key The key which will be used by `merge` to access this particular tic-variable (which may be refined by more than one `GroupKeySpecSource`)
    * @param spec A transform which defines this key part as a function of the source table in `GroupingSource`.
    */
  final case class GroupKeySpecSource(key: CPathField, spec: TransSpec1)    extends GroupKeySpec
  final case class GroupKeySpecAnd(left: GroupKeySpec, right: GroupKeySpec) extends GroupKeySpec
  final case class GroupKeySpecOr(left: GroupKeySpec, right: GroupKeySpec)  extends GroupKeySpec

  object GroupKeySpecSource {
    implicit def apply(kv: String -> TransSpec1) = new GroupKeySpecSource(CPathField(kv._1), kv._2)
  }

  object GroupKeySpec {
    def dnf(keySpec: GroupKeySpec): GroupKeySpec = {
      keySpec match {
        case GroupKeySpecSource(key, spec)                  => GroupKeySpecSource(key, spec)
        case GroupKeySpecAnd(GroupKeySpecOr(ol, or), right) => GroupKeySpecOr(dnf(GroupKeySpecAnd(ol, right)), dnf(GroupKeySpecAnd(or, right)))
        case GroupKeySpecAnd(left, GroupKeySpecOr(ol, or))  => GroupKeySpecOr(dnf(GroupKeySpecAnd(left, ol)), dnf(GroupKeySpecAnd(left, or)))

        case gand @ GroupKeySpecAnd(left, right) =>
          val leftdnf  = dnf(left)
          val rightdnf = dnf(right)
          if (leftdnf == left && rightdnf == right) gand else dnf(GroupKeySpecAnd(leftdnf, rightdnf))

        case gor @ GroupKeySpecOr(left, right) =>
          val leftdnf  = dnf(left)
          val rightdnf = dnf(right)
          if (leftdnf == left && rightdnf == right) gor else dnf(GroupKeySpecOr(leftdnf, rightdnf))
      }
    }

    def toVector(keySpec: GroupKeySpec): Vector[GroupKeySpec] = {
      keySpec match {
        case GroupKeySpecOr(left, right) => toVector(left) ++ toVector(right)
        case x                           => Vector(x)
      }
    }
  }

  object constants {
    val Key   = CPathField("key")
    val Value = CPathField("value")

    object SourceKey {
      val Single = ('key: TransSpec1)
      val Left   = Single.fromLeft
      val Right  = Single.fromRight
    }
    object SourceValue {
      val Single = ('value: TransSpec1)
      val Left   = Single.fromLeft
      val Right  = Single.fromRight
    }
  }
}
