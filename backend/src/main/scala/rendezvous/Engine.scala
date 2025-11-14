package rendezvous.backend

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingRef

import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.collection.immutable.ListMap
import scala.concurrent.duration.*

trait Engine:

  def createNode(maxLife: Option[FiniteDuration]): IO[UUID]

  def removeNode(nodeId: UUID): IO[Unit]

  def addTask(task: Task): IO[Unit]

  def snapshot: IO[ListMap[UUID, Node]]

  def stream: fs2.Stream[IO, ListMap[UUID, Node]]

  def updates: fs2.Stream[IO, (UUID, Task)]

object Engine:

  case object NoNodesAvailable extends RuntimeException("no nodes available")

  def resource(): Resource[IO, Engine] =

    def impl(pubsub: PubSub[UUID]) =
      for
        supervisor <- Supervisor[IO]
        hash <- IO.pure(Hash.mmh3()).toResource
        nodesRef <- SignallingRef.of[IO, ListMap[UUID, Node]](ListMap.empty).toResource
        ranksRef <- SignallingRef.of[IO, Map[UUID, Ranking]](Map.empty).toResource
        fibers <- Ref.of[IO, Map[UUID, Fiber[IO, Throwable, Unit]]](Map.empty).toResource
      yield new Engine:

        def snapshot: IO[ListMap[UUID, Node]] = nodesRef.get

        def stream: fs2.Stream[IO, ListMap[UUID, Node]] =
          nodesRef.discrete.merge(fs2.Stream.repeatEval(nodesRef.get))

        def updates: fs2.Stream[IO, (UUID, Task)] =
          nodesRef.discrete.switchMap: nodes =>
            fs2.Stream.emits(nodes.toList)
              .map: (nodeId, node) =>
                node.updates.tupleLeft(nodeId)
              .parJoinUnbounded

        def addTask(task: Task): IO[Unit] =
          nodesRef.get.map(_.keysIterator.toList)
            .flatTap: currNodes =>
              IO.raiseWhen(currNodes.length == 0)(NoNodesAvailable)
            .flatMap: currNodes =>
              val ranks = new Ranking(task.id, hash, currNodes)
              ranksRef.update(_ + (task.id -> ranks)).as(ranks)
            .flatMap: ranks =>
              ranks.bestNode.foldMapM: node =>
                nodeWithId(node).flatMap(_.foldMapM(_.add(task)))

        def nodeWithId(id: UUID): IO[Option[Node]] = nodesRef.get.map(_.get(id))

        def createNode(maxLife: Option[FiniteDuration]): IO[UUID] =
          IO.randomUUID.flatTap: nodeId =>
            Deferred[IO, Node].flatMap: isAlloc =>
              supervisor.supervise:
                Node.resource(nodeId, maxLife, pubsub).use: node =>
                  isAlloc.complete(node) *> IO.never.as(())
              .flatMap: fib =>
                fibers.update(_ + (nodeId -> fib))
              .flatMap: _ =>
                isAlloc.get.flatMap: node =>
                  nodesRef.update(_ + (nodeId -> node))
              .flatMap: _ =>
                ranksRef.get.flatMap:
                  _.toList.traverse: (taskId, ranks) =>
                    IO(ranks.addNode(nodeId))
                      .flatTap: _ =>
                        ranksRef.update(_ + (taskId -> ranks))
                      .flatTap: newRanks =>
                        newRanks.secondBestNode.foldMapM: node =>
                          nodeWithId(node).flatMap(_.foldMapM(_.remove(Task(taskId))))
                      .flatMap: newRanks =>
                        newRanks.bestNode.foldMapM: node =>
                          nodeWithId(node).flatMap(_.foldMapM(_.add(Task(taskId))))
                  .void

        def removeNode(nodeId: UUID): IO[Unit] =
          ranksRef.get.flatMap:
            _.toList.traverse: (taskId, ranks) =>
              IO(ranks.removeNode(nodeId))
                .flatMap: ranks =>
                  if ranks.nodes.isEmpty then ranksRef.update(_ - taskId)
                  else ranksRef.update(_ + (taskId -> ranks))
          .flatMap: _ =>
            nodeWithId(nodeId).flatMap:
              _.foldMapM: node =>
                fs2.Stream.evalSeq(node.snapshot)
                  .parEvalMapUnbounded: task =>
                    ranksRef.get.flatMap(_.get(task.id).foldMapM: ranks =>
                      ranks.bestNode.foldMapM: node =>
                        nodeWithId(node).flatMap(_.foldMapM(_.add(task))))
                  .compile
                  .drain
          .flatMap: _ =>
            nodesRef.update(_ - nodeId)
          .flatMap: _ =>
            fibers.get.flatMap(_.get(nodeId).foldMapM(_.cancel))
          .flatMap: _ =>
            IO.println(s"Node $nodeId is removed")

    PubSub[UUID].toResource
      .flatMap: heartbeatTopic =>
        impl(heartbeatTopic).flatTap: engine =>
          for
            heartbeatsRef <- SignallingRef.of[IO, Map[UUID, Instant]](Map.empty).toResource
            _ <- heartbeatTopic
              .subscribe
              .evalMap: nodeId =>
                IO.realTimeInstant.flatMap: heartbeatTime =>
                  heartbeatsRef.update(_ + (nodeId -> heartbeatTime))
              .compile
              .drain
              .background
            _ <- heartbeatsRef.discrete.switchMap: heartbeats =>
              fs2.Stream.emits(heartbeats.keysIterator.toSeq)
                .map: nodeId =>
                  fs2.Stream.fixedRateStartImmediately[IO](1.second)
                    .map(_ => heartbeats.get(nodeId))
                    .unNone
                    .evalMap: lastHeartbeat =>
                      IO.realTimeInstant.flatMap: now =>
                        val isDead = Duration.between(lastHeartbeat, now)
                          .toMillis.millis > 3.seconds
                        IO.whenA(isDead):
                          engine.removeNode(nodeId) *> heartbeatsRef.update(_ - nodeId)
                .parJoinUnbounded
            .compile
              .drain
              .background
          yield ()
