/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.qscript.qsu

import slamdata.Predef.{List, Long, Nil}

import quasar.{Qspec, TreeMatchers}
import quasar.Planner.PlannerError
import quasar.ejson.{EJson, Fixed}
import quasar.ejson.implicits._
import quasar.fp.{coproductEqual, coproductShow}
import quasar.qscript.{construction, ExcludeId, Hole, MapFuncsCore, ReduceFuncs, SrcHole}

import matryoshka.{delayEqual, delayShow, showTShow, Embed}
import matryoshka.data.Fix
import matryoshka.data.free._
import matryoshka.patterns.CoEnv
import scalaz.{\/, -\/, \/-, EitherT, State}

object ReifyBucketsSpec extends Qspec with QSUTTypes[Fix] with TreeMatchers {
  import QSUGraph.Extractors._
  import QScriptUniform.{DTrans, Retain, Rotation}

  val J = Fixed[Fix[EJson]]
  val qsu = QScriptUniform.DslT[Fix]
  val func = construction.Func[Fix]

  def reifyBuckets(qsu: Fix[QScriptUniform]): PlannerError \/ QSUGraph =
    ApplyProvenance[Fix, EitherT[State[Long, ?], PlannerError, ?]](QSUGraph.fromTree(qsu))
      .flatMap(ReifyBuckets[Fix, EitherT[State[Long, ?], PlannerError, ?]])
      .map(_.graph)
      .run.eval(0)

  "reifying buckets" should {
    "extract mappable region up to bucket source and use in reducer" >> {
      // select sum(pop) from zips group by state
      val tree =
        qsu.lpReduce(
          qsu.map(
            qsu.dimEdit(
              qsu.tread1("zips"),
              DTrans.Group(
                func.ProjectKeyS(func.Hole, "state"))),
            func.ProjectKeyS(func.Hole, "pop")),
          ReduceFuncs.Sum(()))

      val expBucket =
        func.ProjectKeyS(func.Hole, "state") map (Access.value[Fix[EJson], Hole](_))

      val expReducer =
        func.ProjectKeyS(func.Hole, "pop")

      reifyBuckets(tree) must beLike {
        case \/-(QSReduce(
            LeftShift(Read(_), _, ExcludeId, _, Rotation.ShiftMap),
            List(b),
            List(ReduceFuncs.Sum(r)),
            _)) =>

          (b must beTreeEqual(expBucket)) and
          (r must beTreeEqual(expReducer))
      }
    }

    "extract func even when constant buckets" >> {
      // select sum(pop) from zips group by 7
      val tree =
        qsu.lpReduce(
          qsu.map(
            qsu.dimEdit(
              qsu.tread1("zips"),
              DTrans.Group(func.Constant(J.int(7)))),
            func.ProjectKeyS(func.Hole, "pop")),
          ReduceFuncs.Sum(()))

      val expB =
        func.Constant[Access[Fix[EJson], Hole]](J.int(7))

      val expReducer =
        func.ProjectKeyS(func.Hole, "pop")

      reifyBuckets(tree) must beLike {
        case \/-(
          QSReduce(
            LeftShift(Read(_), _, ExcludeId, _, _),
            List(b),
            List(ReduceFuncs.Sum(r)),
            _)) =>

          (b must beTreeEqual(expB)) and
          (r must beTreeEqual(expReducer))
      }
    }

    "leave source untouched when no buckets" >> {
      // select sum(pop) from zips
      val tree =
        qsu.lpReduce(
          qsu.map(
            qsu.tread1("zips"),
            func.ProjectKeyS(func.Hole, "pop")),
          ReduceFuncs.Sum(()))

      val expFm =
        func.ProjectKeyS(func.Hole, "pop")

      reifyBuckets(tree) must beLike {
        case \/-(
          QSReduce(
            Map(LeftShift(Read(_), _, ExcludeId, _, _), fm),
            Nil,
            List(ReduceFuncs.Sum(r)),
            _)) =>

          (fm must beTreeEqual(expFm)) and
          (r must beTreeEqual(func.Hole))
      }
    }

    "support dimension-altering reduce expressions" >> {
      // select city, sum(loc[*]) from zips group by city
      val src =
        qsu.dimEdit(
          qsu.tread1("zips"),
          DTrans.Group(func.ProjectKeyS(func.Hole, "city")))

      val tree =
        qsu.autojoin2((
          qsu.autojoin2((
            qsu.cstr("city"),
            qsu.lpReduce(
              qsu.autojoin2((
                src,
                qsu.cstr("city"),
                _(MapFuncsCore.ProjectKey(_, _)))),
              ReduceFuncs.Arbitrary(())),
            _(MapFuncsCore.MakeMap(_, _)))),
          qsu.autojoin2((
            qsu.cstr("1"),
            qsu.lpReduce(
              qsu.transpose(
                qsu.autojoin2((
                  src,
                  qsu.cstr("loc"),
                  _(MapFuncsCore.ProjectKey(_, _)))),
                Retain.Values,
                Rotation.FlattenArray),
              ReduceFuncs.Sum(())),
            _(MapFuncsCore.MakeMap(_, _)))),
          _(MapFuncsCore.ConcatMaps(_, _))))

      val expArbBucket =
        func.ProjectKeyS(func.Hole, "city") map (Access.value[Fix[EJson], Hole](_))

      val expArbExpr =
        func.ProjectKeyS(func.Hole, "city")

      val expSumBucket =
        func.ProjectKeyS(func.ProjectKeyS(func.Hole, "grouped"), "city") map (Access.value[Fix[EJson], Hole](_))

      val expSumExpr =
        func.ProjectKeyS(func.Hole, "reduce_expr")

      reifyBuckets(tree) must beLike {
        case \/-(
          AutoJoin2(
            AutoJoin2(
              city,
              QSReduce(
                LeftShift(
                  Read(_),
                  Embed(CoEnv(-\/(SrcHole))),
                  ExcludeId,
                  _,
                  Rotation.ShiftMap),
                List(arbBucket),
                List(ReduceFuncs.Arbitrary(arbExpr)),
                _),
              _),
            AutoJoin2(
              str1,
              QSReduce(
                AutoJoin2(
                  LeftShift(
                    Read(_),
                    Embed(CoEnv(-\/(SrcHole))),
                    ExcludeId,
                    _,
                    Rotation.ShiftMap),
                  LeftShift(
                    AutoJoin2(
                      LeftShift(
                        Read(_),
                        Embed(CoEnv(-\/(SrcHole))),
                        ExcludeId,
                        _,
                        Rotation.ShiftMap),
                      loc,
                      _),
                    Embed(CoEnv(-\/(SrcHole))),
                    ExcludeId,
                    tgt,
                    Rotation.FlattenArray),
                  _),
                List(sumBucket),
                List(ReduceFuncs.Sum(sumExpr)),
                _),
              _),
            _)) =>

          arbBucket must beTreeEqual(expArbBucket)
          arbExpr must beTreeEqual(expArbExpr)
          sumBucket must beTreeEqual(expSumBucket)
          sumExpr must beTreeEqual(expSumExpr)
      }
    }

    // TODO: When does a bucketed sort arise?
    "reify sort buckets" >> todo
  }
}
