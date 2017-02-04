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

package quasar.physical.mongodb

import quasar.Predef._
import quasar.{NameGenerator => QNameGenerator}
import quasar.connector.{EnvironmentError, EnvErrT}
import quasar.contrib.scalaz.eitherT._
import quasar.fp.kleisli._
import quasar.fs._
import quasar.javascript._
import quasar.physical.mongodb.execution._
import quasar.physical.mongodb.workflow._
import quasar.physical.mongodb.workflowtask._
import WorkflowExecutionError._

import matryoshka._
import matryoshka.data.Fix
import matryoshka.implicits._
import scalaz._, Scalaz._

/** Implements the necessary operations for executing a `Workflow` against
  * MongoDB.
  */
private[mongodb] abstract class WorkflowExecutor[F[_]: Monad, C] {
  import MapReduce._
  import WorkflowExecutor.WorkflowCursor

  /** Execute the given aggregation pipeline with the given collection as
    * input.
    */
  protected def aggregate(src: Collection, pipeline: Pipeline): F[Unit]

  /** Returns a cursor to the results of evaluating the given aggregation
    * pipeline on the provided collection.
    */
  protected def aggregateCursor(src: Collection, pipeline: Pipeline): F[C]

  /** Returns a cursor to the result of counting the documents matched by the
    * query, with the result at the given field name in the output document.
    */
  protected def count(src: Collection, cfg: Count): F[Long]

  /** Returns a cursor to the distinct documents matched by the query
    * collection, with results available at the given field name in the
    * output document.
    */
  protected def distinct(src: Collection, cfg: Distinct, field: BsonField.Name): F[C]

  /** Drop the given collection. */
  protected def drop(coll: Collection): F[Unit]

  /** Returns a cursor to the documents matching the `Find` query in the
    * given collection.
    */
  protected def find(src: Collection, cfg: Find): F[C]

  /** Insert the given BSON documents into the specified collection. */
  protected def insert(dst: Collection, values: List[Bson.Doc]): F[Unit]

  /** Execute the given MapReduce job, sourcing values from the given `src`
    * collection and writing the results to the destination collection.
    */
  protected def mapReduce(src: Collection, dst: OutputCollection, mr: MapReduce): F[Unit]

  /** Returns a cursor to the results of evaluating the given MapReduce job on
    * the provided collection.
    */
  protected def mapReduceCursor(src: Collection, mr: MapReduce): F[C]

  //--- Derived Methods ---

  type G0[A]  = StateT[F, Long, A]
  type G[A]   = ReaderT[G0, CollectionName, A]
  type M[A]   = WorkflowExecErrT[G, A]

  /** Returns a stream of the results generated by evaluating the given
    * workflow.
    *
    * @param workflow the crystallized `Workflow` to evaluate
    * @param defaultDb the database to use for temp collections when one is
    *                  required and unable to be determined from the workflow.
    *                  If not provided, a `NoDatabase` error is returned instead.
    */
  def evaluate(
    workflow: Crystallized[WorkflowF],
    defaultDb: Option[DatabaseName]
  ): M[List[Bson] \/ WorkflowCursor[C]] =
    task(workflow) match {
      case PureTask(Bson.Arr(bsons)) =>
        bsons.left[WorkflowCursor[C]].point[M]

      case PureTask(bson) =>
        List(bson).left[WorkflowCursor[C]].point[M]

      case ReadTask(coll) =>
        asWorkflowCursor(findAll(coll))

      case PipelineTask(ReadTask(coll), pipeline) =>
        asM(evalPipeline(coll, pipeline))
          .map(_ bimap (List(_), WorkflowCursor(_, none)))

      case MapReduceTask(ReadTask(coll), mr, _) =>
        asWorkflowCursor(mapReduceCursor(coll, mr))

      case PipelineTask(src, pipeline) =>
        executeToCursor(defaultDb, src, evalPipeline(_, pipeline))

      case MapReduceTask(src, mr, _) =>
        executeToCursor(defaultDb, src, c => mapReduceCursor(c, mr) map (_.right))

      case foldl @ FoldLeftTask(_, _) =>
        executeToCursor(defaultDb, foldl, c => findAll(c) map (_.right))
    }

  /** Returns the `Collection` containing the results of executing the given
    * (crystallized) `Workflow`.
    */
  def execute(workflow: Crystallized[WorkflowF], dst: Collection): M[Collection] =
    execute0(task(workflow), dst).run(Set()) flatMap { case (tmps, coll) =>
      (tmps - coll) traverse_ (c => asM(drop(c))) as coll
    }

  /** Returns a cursor to all the documents in the given collection. */
  protected def findAll(src: Collection): F[C] =
    find(src, Find(none, none, none, none, none))

  /** Returns the count of the documents matched by the query, with the result
    * available at the given field name in the output document.
    */
  protected def labeledCount(src: Collection, cfg: Count, field: BsonField.Name): F[Bson] =
    count(src, cfg) map (n => Bson.Doc(ListMap(field.asText -> Bson.Int64(n))))

  ////

  // The set of temp collections written to in the computation
  private type Temps           = Set[Collection]
  private type TempsT[X[_], A] = StateT[X, Temps, A]
  private type N[A]            = TempsT[M, A]

  // NB: This handholding was necessary to resolve "divering implicit
  //     expansion" errors for Monad[M] when attempting to summon Monad[N].
  private implicit val N: Monad[N] =
    StateT.stateTMonadState[Temps, M](Monad[M])

  private val asM: F ~> M =
    new (F ~> M) {
      def apply[A](fa: F[A]) =
        (fa.liftM[StateT[?[_], Long, ?]]
          .liftM[ReaderT[?[_], CollectionName, ?]]: G[A])
          .liftM[WorkflowExecErrT]
    }

  private def asWorkflowCursor(c: F[C]): M[List[Bson] \/ WorkflowCursor[C]] =
    asM(c) map (WorkflowCursor(_, none)) map (_.right[List[Bson]])

  private def executeToCursor(
    defDb: Option[DatabaseName],
    src: WorkflowTask,
    f: Collection => F[Bson \/ C]
  ): M[List[Bson] \/ WorkflowCursor[C]] = {
    val tempDbName = src.foldMap {
      case Fix(ReadTaskF(Collection(dbName, _))) => List(dbName)
      case _ => Nil
    }.headOption orElse defDb

    val tempDst: N[Collection] =
      tempDbName cata (
        tempColl,
        noDatabase(()).raiseError[M, Collection].liftM[TempsT])

    val srcResult =
      tempDst flatMap (d => execute0(src, d) strengthL d)

    srcResult run Set() flatMap { case (temps, (d, c)) =>
      val tmpSrc = some(c) filter (_ == d)
      asM((temps - c).traverse_(drop) *> f(c))
        .map(_ bimap (List(_), WorkflowCursor(_, tmpSrc)))
    }
  }

  private def execute0(wt: WorkflowTask, out: Collection)
    (implicit ev: WorkflowOpCoreF :<: WorkflowF)
    : N[Collection] = {
    def unableToStore[A](bson: Bson): N[A] =
      insertFailed(
        bson,
        s"MongoDB is only able to store documents in collections, not `$bson`."
      ).raiseError[M, A].liftM[TempsT]

    wt match {
      case PureTask(doc @ Bson.Doc(_)) =>
        asM(insert(out, List(doc)))
          .liftM[TempsT]
          .as(out)

      case PureTask(Bson.Arr(vs)) =>
        for {
          docs <- vs.toList.traverse {
                    case doc @ Bson.Doc(_) => doc.right
                    case other             => other.left
                  } fold (unableToStore[List[Bson.Doc]], _.point[N])
          _    <- asM(insert(out, docs)).liftM[TempsT]
        } yield out

      case PureTask(v) =>
        unableToStore(v)

      case ReadTask(coll) =>
        // Update the state to reflect that `out` wasn't used.
        wroteTo(out, false) as coll

      case PipelineTask(source, pipeline) =>
        for {
          tmp <- tempColl(out.database)
          src <- execute0(source, tmp)
          _   <- asM(aggregate(src, pipeline ::: List(PipelineOp($OutF((), out.collection).shapePreserving))))
                   .liftM[TempsT]
        } yield out

      case MapReduceTask(source, mr, oa) =>
        for {
          tmp <- tempColl(out.database)
          src <- execute0(source, tmp)
          act  = oa getOrElse Action.Replace
          _   <- asM(mapReduce(src, outputCollection(out, act), mr))
                   .liftM[TempsT]
        } yield out

      case FoldLeftTask(rd @ ReadTask(_), _) =>
        invalidTask(rd, "FoldLeft from simple read")
          .raiseError[M, Collection].liftM[TempsT]

      case FoldLeftTask(head, tail) =>
        for {
          h <- execute0(head, out)
          _ <- tail.traverse_[N] {
                 case MapReduceTask(source, mr, Some(act)) =>
                   tempColl(h.database) flatMap (execute0(source, _)) flatMap { src =>
                     asM(mapReduce(src, outputCollection(h, act), mr)).liftM[TempsT]
                   }

                 case mrt @ MapReduceTask(_, _, _) =>
                   invalidTask(mrt, "no output action specified for mapReduce in FoldLeft")
                     .raiseError[M, Unit].liftM[TempsT]

                 case other =>
                   invalidTask(other, "un-mergable FoldLeft input")
                     .raiseError[M, Unit].liftM[TempsT]
               }
        } yield h
    }
  }

  /** This tries to turn a Pipeline into a simpler operation (eg, `count()` or
    * `find()`) and falls back to a pipeline if it can’t.
    */
  // TODO: This should really be handled when building the WorkflowTask, but
  //       currently that phase knows neither whether the DB is read-only, nor
  //       if the user wants a cursor.
  private def evalPipeline(src: Collection, pipeline: Pipeline): F[Bson \/ C] = {
    val (pl, (skip, limit)) = extractRange(pipeline)
    pl match {
      case (Nil, Nil) =>
        find(src, Find(None, None, None, skip, limit)) map (_.right)
      case (List(PipelineOpCore($MatchF((), sel))), Nil) =>
        find(src, Find(sel.some, None, None, skip, limit)) map (_.right)
      case (List(Projectable(bson)), Nil) =>
        find(src, Find(None, bson.some, None, skip, limit)) map (_.right)
      case (Nil, List(Projectable(bson))) =>
        find(src, Find(None, bson.some, None, skip, limit)) map (_.right)
      case (List(PipelineOpCore($SortF((), keys))), Nil) =>
        find(src, Find(None, None, keys.some, skip, limit)) map (_.right)
      case (List(PipelineOpCore($MatchF((), sel)), Projectable(bson)), Nil) =>
        find(src, Find(sel.some, bson.some, None, skip, limit)) map (_.right)
      case (List(PipelineOpCore($MatchF((), sel))), List(Projectable(bson))) =>
        find(src, Find(sel.some, bson.some, None, skip, limit)) map (_.right)
      case (List(PipelineOpCore($MatchF((), sel)), PipelineOpCore($SortF((), keys))), Nil) =>
        find(src, Find(sel.some, None, keys.some, skip, limit)) map (_.right)
      case (List(Projectable(bson), PipelineOpCore($SortF((), keys))), Nil) =>
        find(src, Find(None, bson.some, keys.some, skip, limit)) map (_.right)
      case (List(PipelineOpCore($MatchF((), sel)), Projectable(bson), PipelineOpCore($SortF((), keys))), Nil) =>
        find(src, Find(sel.some, bson.some, keys.some, skip, limit)) map (_.right)
      case (List(Countable(field)), Nil)
          if skip.getOrElse(0L) ≟ 0L && limit.cata(_ >= 1L, true) =>
        labeledCount(src, Count(None, None, None), field) map (_.left)
      case (Nil, List(Countable(field))) =>
        labeledCount(src, Count(None, skip, limit), field) map (_.left)
      case (List(PipelineOpCore($MatchF((), sel)), Countable(field)), Nil)
          if skip.getOrElse(0L) ≟ 0L && limit.cata(_ >= 1L, true) =>
        labeledCount(src, Count(sel.some, None, None), field) map (_.left)
      case (List(PipelineOpCore($MatchF((), sel))), List(Countable(field))) =>
        labeledCount(src, Count(sel.some, skip, limit), field) map (_.left)
      case (Distinctable(origField, newField), Nil) if skip ≟ None && limit ≟ None =>
        distinct(src, Distinct(origField, None), newField) map (_.right)
      case (PipelineOpCore($MatchF((), sel)) :: Distinctable(origField, newField), Nil) if skip ≟ None && limit ≟ None =>
        distinct(src, Distinct(origField, sel.some), newField) map (_.right)
      case _ => aggregateCursor(src, pipeline) map (_.right)
    }
  }

  private def wroteTo(c: Collection, didWrite: Boolean): N[Unit] =
    Lens.setMembershipLens(c).assign(didWrite).void.lift[M]

  private def tempColl(dbName: DatabaseName): N[Collection] =
    for {
      pfx <- MonadReader[M, CollectionName].ask.liftM[TempsT]
      tmp <- QNameGenerator[M].prefixedName(pfx.value).liftM[TempsT]
      col =  Collection(dbName, CollectionName(tmp))
      _   <- wroteTo(col, true) // assume we'll write to this collection
    } yield col

  private def outputCollection(c: Collection, a: Action) =
    OutputCollection(
      c.collection,
      Some(ActionedOutput(a, Some(c.database), None)))
}

object WorkflowExecutor {
  /** A cursor to the result of evaluating a `Workflow`, optionally paired with
    * the cursor's temporary source that should be dropped once the cursor is
    * closed.
    */
  final case class WorkflowCursor[C](cursor: C, tempSrc: Option[Collection])

  object WorkflowCursor {
    implicit def workflowCursorDataCursor[C](
      implicit C: DataCursor[MongoDbIO, C]
    ): DataCursor[MongoDbIO, WorkflowCursor[C]] =
      new DataCursor[MongoDbIO, WorkflowCursor[C]] {
        def nextChunk(wfc: WorkflowCursor[C]) =
          C.nextChunk(wfc.cursor)

        def close(wfc: WorkflowCursor[C]) =
          C.close(wfc.cursor).attempt *>
            wfc.tempSrc.cata(MongoDbIO.dropCollection, ().point[MongoDbIO])
      }
  }

  /** A `WorkflowExecutor` that executes a `Workflow` in the `MongoDbIO` monad. */
  val mongoDb: EnvErrT[MongoDbIO, WorkflowExecutor[MongoDbIO, BsonCursor]] = {
    import MongoDbIOWorkflowExecutor._
    import EnvironmentError._

    type M[A]    = EnvErrT[MongoDbIO, A]
    type WFExec  = WorkflowExecutor[MongoDbIO, BsonCursor]

    liftEnvErr(MongoDbIO.serverVersion) flatMap { v =>
      if (v >= ServerVersion.MongoDb2_6)
        (new MongoDbIOWorkflowExecutor: WFExec).point[M]
      else
        unsupportedVersion("MongoDB", v.shows).raiseError[M, WFExec]
    }
  }

  /** A 'WorkflowExecutor` that interprets a `Workflow` in JavaScript. */
  val javaScript: WorkflowExecutor[JavaScriptLog, Unit] =
    new JavaScriptWorkflowExecutor

  /** Interpret a `Workflow` into an equivalent JavaScript program. */
  def toJS(workflow: Crystallized[WorkflowF]): WorkflowExecutionError \/ String =
    javaScript.evaluate(workflow, none).run.run(CollectionName("tmp.gen_")).eval(0).run match {
      case (log, r) if log.isEmpty => r as ""
      case (log, r)                => r as Js.Stmts(log.toList).pprint(0)
    }
}
