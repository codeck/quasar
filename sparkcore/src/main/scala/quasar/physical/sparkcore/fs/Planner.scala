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

package quasar.physical.sparkcore.fs

import simulacrum.typeclass
import quasar.Predef._
import quasar.Data
import quasar.DataCodec
import quasar._
import quasar.Planner._
import quasar.fp._
import quasar.qscript._
import quasar.contrib.pathy.AFile

import org.apache.spark._
import org.apache.spark.rdd._
import matryoshka.{Hole => _, _}
import scalaz._, Scalaz._
import scalaz.concurrent.Task
// 

@typeclass trait Planner[F[_]] {
  type IT[G[_]]

  def plan(fromFile: (SparkContext, AFile) => Task[RDD[String]]): AlgebraM[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?], F, RDD[Data]]

}

object Planner {
  
  type Aux[T[_[_]], F[_]] = Planner[F] { type IT[G[_]] = T[G] }

  def unreachable[T[_[_]], F[_]]: Planner.Aux[T, F] =
    new Planner[F] {
      type IT[G[_]] = T[G]
      def plan(fromFile: (SparkContext, AFile) => Task[RDD[String]]): AlgebraM[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?], F, RDD[Data]] =
        _ =>  StateT((sc: SparkContext) => {
        EitherT(InternalError("unreachable").left[(SparkContext, RDD[Data])].point[Task])
      })
    }

  implicit def deadEnd[T[_[_]]]: Planner.Aux[T, Const[DeadEnd, ?]] = unreachable
  implicit def read[T[_[_]]]: Planner.Aux[T, Const[Read, ?]] = unreachable
  implicit def projectBucket[T[_[_]]]: Planner.Aux[T, ProjectBucket[T, ?]] = unreachable
  implicit def thetaJoin[T[_[_]]]: Planner.Aux[T, ThetaJoin[T, ?]] = unreachable

  implicit def shiftedread[T[_[_]]]: Planner.Aux[T, Const[ShiftedRead, ?]] =
    new Planner[Const[ShiftedRead, ?]] {
      type IT[G[_]] = T[G]
      def plan(fromFile: (SparkContext, AFile) => Task[RDD[String]]) =
        (qs: Const[ShiftedRead, RDD[Data]]) => {
          StateT((sc: SparkContext) => {
            val filePath = qs.getConst.path
            EitherT(fromFile(sc, filePath).map { initRDD =>
              val rdd = initRDD.map { raw =>
                DataCodec.parse(raw)(DataCodec.Precise).fold(error => Data.NA, ι)
              }
                (sc, rdd).right[PlannerError]

            })
          })
        }
    }

  implicit def qscriptCore[T[_[_]]: Recursive: ShowT]:
      Planner.Aux[T, QScriptCore[T, ?]] =
    new Planner[QScriptCore[T, ?]] {
      type IT[G[_]] = T[G]
      def plan(fromFile: (SparkContext, AFile) => Task[RDD[String]]): AlgebraM[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?], QScriptCore[T, ?], RDD[Data]] = {
        case qscript.Map(src, f) =>
          StateT((sc: SparkContext) =>
            EitherT {
              val maybeFunc =
                freeCataM(f)(interpretM(κ(ι[Data].right[PlannerError]), CoreMap.change))
              maybeFunc.map(df => (sc, src.map(df))).point[Task]
            }
          )
        case Reduce(src, bucket, reducers, repair) =>
          ??? // select [sum(pop), max(pop)] from cities group by state
        case Sort(src, bucket, order) =>
          ???
        case Filter(src, f) =>
          StateT((sc: SparkContext) =>
            EitherT {
              val maybeFunc =
                freeCataM(f)(interpretM(κ(ι[Data].right[PlannerError]), CoreMap.change))
              maybeFunc.map(df => (sc, src.filter{
                df >>> {
                  case Data.Bool(b) => b
                  case _ => false
                }
              })).point[Task]
            }
          )
        case Take(src, from, count) =>
          val algebraM = Planner[QScriptTotal[T, ?]].plan(fromFile)
          val srcState = src.point[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?]]

          // StateT[Task[Planner \/ A]]
          // EitherT[Task, State[SC, A]]
          // Task[PllE \/ State[SC, A]]


          val fromState = freeCataM(from)(interpretM(κ(srcState), algebraM))
          val countState = freeCataM(count)(interpretM(κ(srcState), algebraM))

          val countEval = countState >>= (rdd => EitherT(Task.delay(rdd.first match {
            case Data.Int(v) if v.isValidLong => v.toLong.right[PlannerError]
            case Data.Int(v) => InternalError(s"Unsupported plan").left[Long]
            case a => InternalError(s"$a is not a Long number").left[Long]
          })).liftM[StateT[?[_], SparkContext, ?]])
          (fromState |@| countEval)((rdd, count) =>
            rdd.zipWithIndex.filter(_._2 < count).map(_._1))

        case Drop(src, from, count) =>
          val algebraM = Planner[QScriptTotal[T, ?]].plan(fromFile)
          val srcState = src.point[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?]]

          val fromState = freeCataM(from)(interpretM(κ(srcState), algebraM))
          val countState = freeCataM(count)(interpretM(κ(srcState), algebraM))

          val countEval = countState >>= (rdd => EitherT(Task.delay(rdd.first match {
            case Data.Int(v) if v.isValidLong => v.toLong.right[PlannerError]
            case Data.Int(v) => InternalError(s"Unsupported plan").left[Long]
            case a => InternalError(s"$a is not a Long number").left[Long]
          })).liftM[StateT[?[_], SparkContext, ?]])
          (fromState |@| countEval)((rdd, count) =>
            rdd.zipWithIndex.filter(_._2 >= count).map(_._1))

        case LeftShift(src, struct, repair) =>

          val structFunc =
            freeCataM(struct)(interpretM(κ(ι[Data].right[PlannerError]), CoreMap.change))

          def repairFunc: PlannerError \/ ((Data, Data) => Data) ={
            val dd = freeCataM(repair)(interpretM[PlannerError \/ ?, MapFunc[T, ?], JoinSide, Data => Data]({
              case LeftSide => ((x: Data) => x match {
                case Data.Arr(elems) => elems(0)
                case _ => Data.NA
              }).right
              case RightSide => ((x: Data) => x match {
                case Data.Arr(elems) => elems(1)
                case _ => Data.NA
              }).right
            }, CoreMap.change))

            dd.map(df => (l, r) => df(Data.Arr(List(l, r))))
          }

          StateT((sc: SparkContext) =>
            EitherT((for {
              df <- structFunc
              rf <- repairFunc
              ls = (input: Data) => df(input) match {
                case Data.Arr(list) => list.map(rf(input, _))
                case Data.Obj(m) => m.values.map(rf(input, _))
                case _ => List.empty[Data]
              }
            } yield {
              src.flatMap(ls)
            }).map((sc, _)).point[Task]))

          // TODO util function that lifts EIther to StateT[EitherT[Task]]


          // StateT((sc: SparkContext) => {
          // EitherT((sc, src).right[PlannerError].point[Task])
        // })
        case Union(src, lBranch, rBranch) =>
          val algebraM = Planner[QScriptTotal[T, ?]].plan(fromFile)
          val srcState = src.point[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?]]

          for {
            left <- freeCataM(lBranch)(interpretM(κ(srcState), algebraM))
            right <- freeCataM(rBranch)(interpretM(κ(srcState), algebraM))
          } yield left ++ right
        case Unreferenced() =>
          StateT((sc: SparkContext) => {
            EitherT((sc, sc.parallelize(List(Data.Null: Data))).right[PlannerError].point[Task])
          })
      }
    }
  
  implicit def equiJoin[T[_[_]]: Recursive: ShowT]:
      Planner.Aux[T, EquiJoin[T, ?]] =
    new Planner[EquiJoin[T, ?]] {
      type IT[G[_]] = T[G]
      def plan(fromFile: (SparkContext, AFile) => Task[RDD[String]]): AlgebraM[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?], EquiJoin[T, ?], RDD[Data]] = _ => ???      
    }
  
  // TODO: Remove this instance

  
  implicit def coproduct[T[_[_]]: Recursive: ShowT, F[_], G[_]](
    implicit F: Planner.Aux[T, F], G: Planner.Aux[T, G]):
      Planner.Aux[T, Coproduct[F, G, ?]] =
    new Planner[Coproduct[F, G, ?]] {
      type IT[G[_]] = T[G]
      def plan(fromFile: (SparkContext, AFile) => Task[RDD[String]]): AlgebraM[StateT[EitherT[Task, PlannerError, ?], SparkContext, ?], Coproduct[F, G, ?], RDD[Data]] = _.run.fold(F.plan(fromFile), G.plan(fromFile))
    }
}
