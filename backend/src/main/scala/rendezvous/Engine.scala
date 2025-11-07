package rendezvous.backend.rendezvous

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import rendezvous.backend.rendezvous.Data
import rendezvous.backend.rendezvous.Hash
import rendezvous.backend.rendezvous.Node

import java.util.UUID
import scala.collection.immutable.SortedMap

trait Engine:

  def createNode: IO[Unit]

  def removeNode(nodeId: UUID): IO[Unit]

  def addData(data: Data): IO[Unit]

  def nodes: fs2.Stream[IO, Map[UUID, Node]]

  def updates: fs2.Stream[IO, (UUID, Data)]

object Engine:

  case class NoNodesAvailable() extends RuntimeException("no nodes available")

  def apply(): Resource[IO, Engine] =
    for
      supervisor <- Supervisor[IO]
      hash <- IO.unit.as(Hash.mmh3()).toResource
      nodesRef <- SignallingRef.of[IO, SortedMap[UUID, Node]](SortedMap.empty).toResource
      scoresRef <- SignallingRef.of[IO, Map[UUID, List[UUID]]](Map.empty).toResource
      fibers <- Ref.of[IO, Map[UUID, Fiber[IO, Throwable, Unit]]](Map.empty).toResource
    yield new Engine:

      def nodes: fs2.Stream[IO, Map[UUID, Node]] =
        scoresRef.discrete.switchMap(_ => nodesRef.discrete)

      def updates: fs2.Stream[IO, (UUID, Data)] =
        nodesRef.discrete.switchMap: nodes =>
          fs2.Stream.emits(nodes.toList)
            .map: (nodeId, node) =>
              node.updates.tupleLeft(nodeId)
            .parJoinUnbounded

      def addDataImpl(data: Data): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          scoresRef.get.flatMap: scores =>
            scores.get(data.id).foldMapM: sortedNodes =>
              sortedNodes.lastOption.foldMapM: nodeId =>
                nodes.get(nodeId).foldMapM: node =>
                  node.add(data)

      def redistributeData(): IO[Unit] =
        fs2.Stream.evals(nodesRef.get)
          .parEvalMapUnbounded: node =>
            fs2.Stream
              .evalSeq(node.snapshot)
              .parEvalMapUnbounded(addDataImpl)
              .compile
              .drain
          .compile
          .drain

      def newScoresOf(dataId: UUID): IO[List[UUID]] =
        nodesRef.get.map: nodes =>
          nodes.keys.toList
            .sortBy(nodeId => hash.hash(s"$dataId${nodeId}"))

      def addData(data: Data): IO[Unit] =
        newScoresOf(data.id)
          .flatTap: scores =>
            IO.raiseWhen(scores.length == 0)(throw new NoNodesAvailable)
          .flatMap: scores =>
            scoresRef.update(_ + (data.id -> scores))
          .flatMap(_ => addDataImpl(data))

      def createNode: IO[Unit] =
        IO.randomUUID.flatMap: nodeId =>
          supervisor.supervise:
            Node.resource(nodeId).use: node =>
              nodesRef.update(_ + (nodeId -> node)) *>
                IO.never.as(())
          .flatMap: fib =>
            fibers.update(_ + (nodeId -> fib))
          .flatMap: _ =>
            scoresRef.get
              .flatMap: scores =>
                scores.keys.toList.traverse: dataId =>
                  newScoresOf(dataId).tupleLeft(dataId)
                .map(_.toMap)
              .flatMap: newScores =>
                scoresRef.update(_ => newScores)
          .flatMap: _ =>
            redistributeData()

      def redistributeDataOfNode(nodeId: UUID): IO[Unit] =
        nodesRef.get
          .flatMap: nodes =>
            nodes.get(nodeId).foldMapM: node =>
              fs2.Stream
                .evalSeq(node.snapshot)
                .parEvalMapUnbounded: data =>
                  scoresRef
                    .update(_.updatedWith(data.id)(_.map(_.dropRight(1))))
                    .flatMap(_ => addDataImpl(data))
                .compile
                .drain

      def removeNode(nodeId: UUID): IO[Unit] =
        redistributeDataOfNode(nodeId)
          .flatMap: _ =>
            nodesRef.update(_ - nodeId)
          .flatMap: _ =>
            fibers.get.flatMap(_.get(nodeId).foldMapM(_.cancel))
          .flatMap: _ =>
            IO.println(s"Node $nodeId is removed")
