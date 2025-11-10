package rendezvous.backend

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingRef

import java.util.UUID
import scala.collection.immutable.ListMap
import cats.effect.kernel.Deferred

trait Engine:

  def createNode: IO[UUID]

  def removeNode(nodeId: UUID): IO[Unit]

  def addData(data: Data): IO[Unit]

  def snapshot: IO[ListMap[UUID, Node]]

  def stream: fs2.Stream[IO, ListMap[UUID, Node]]

  def updates: fs2.Stream[IO, (UUID, Data)]

object Engine:

  case class NoNodesAvailable() extends RuntimeException("no nodes available")

  def resource(): Resource[IO, Engine] =
    for
      supervisor <- Supervisor[IO]
      hash <- IO.unit.as(Hash.mmh3()).toResource
      nodesRef <- SignallingRef.of[IO, ListMap[UUID, Node]](ListMap.empty).toResource
      scoresRef <- SignallingRef.of[IO, Map[UUID, Scores]](Map.empty).toResource
      fibers <- Ref.of[IO, Map[UUID, Fiber[IO, Throwable, Unit]]](Map.empty).toResource
    yield new Engine:

      def snapshot: IO[ListMap[UUID, Node]] = nodesRef.get

      def stream: fs2.Stream[IO, ListMap[UUID, Node]] =
        nodesRef.discrete.merge(fs2.Stream.repeatEval(nodesRef.get))

      def updates: fs2.Stream[IO, (UUID, Data)] =
        nodesRef.discrete.switchMap: nodes =>
          fs2.Stream.emits(nodes.toList)
            .map: (nodeId, node) =>
              node.updates.tupleLeft(nodeId)
            .parJoinUnbounded

      def addData(data: Data): IO[Unit] =
        nodesRef.get.map(_.keysIterator.toList)
          .flatTap: currNodes =>
            IO.raiseWhen(currNodes.length == 0)(throw new NoNodesAvailable)
          .flatMap: currNodes =>
            val scores = new Scores(data.id, hash, currNodes)
            scoresRef.update(_ + (data.id -> scores)) *> IO.pure(scores)
          .flatMap: scores =>
            scores.bestNode.foldMapM: node =>
              nodeWithId(node).flatMap(_.foldMapM(_.add(data)))

      def nodeWithId(id: UUID): IO[Option[Node]] = nodesRef.get.map(_.get(id))

      def createNode: IO[UUID] =
        IO.randomUUID.flatTap: nodeId =>
          Deferred[IO, Node].flatMap: nodeAllocated =>
            supervisor.supervise:
              Node.resource(nodeId).use: node =>
                nodeAllocated.complete(node) *> IO.never.as(())
            .flatMap: fib =>
              fibers.update(_ + (nodeId -> fib))
            .flatMap: _ =>
              nodeAllocated.get.flatMap: node =>
                nodesRef.update(_ + (nodeId -> node))
            .flatMap: _ =>
                scoresRef.get.flatMap:
                  _.toList.traverse: (dataId, scores) =>
                    IO(scores.addNode(nodeId))
                      .flatTap: _ =>
                        scoresRef.update(_ + (dataId -> scores))
                      .flatTap: newScores =>
                        newScores.secondBestNode.foldMapM: node =>
                          nodeWithId(node).flatMap(_.foldMapM(_.remove(Data(dataId))))
                      .flatMap: newScores =>
                        newScores.bestNode.foldMapM: node =>
                          nodeWithId(node).flatMap(_.foldMapM(_.add(Data(dataId))))
                  .void

      def removeNode(nodeId: UUID): IO[Unit] =
        scoresRef.get.flatMap:
          _.toList.traverse: (dataId, scores) =>
            IO(scores.removeNode(nodeId))
              .flatMap: scores =>
                if scores.scores.isEmpty then
                  scoresRef.update(_ - dataId)
                else
                  scoresRef.update(_ + (dataId -> scores))
          .void
        .flatMap: _ =>
          nodeWithId(nodeId).flatMap:
            _.foldMapM: node =>
              fs2.Stream.evalSeq(node.snapshot)
                .parEvalMapUnbounded: data =>
                  scoresRef.get.flatMap(_.get(data.id).foldMapM: scores =>
                    scores.bestNode.foldMapM: node =>
                      nodeWithId(node).flatMap(_.foldMapM(_.add(data))))
                .compile
                .drain
        .flatMap: _ =>
          nodesRef.update(_ - nodeId)
        .flatMap: _ =>
          fibers.get.flatMap(_.get(nodeId).foldMapM(_.cancel))
        .flatMap: _ =>
          IO.println(s"Node $nodeId is removed")
