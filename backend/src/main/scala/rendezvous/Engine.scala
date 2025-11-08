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
import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

trait Engine:

  def createNode: IO[Unit]

  def removeNode(nodeId: UUID): IO[Unit]

  def addData(data: Data): IO[Unit]

  def stream: fs2.Stream[IO, ListMap[UUID, Node]]

  def updates: fs2.Stream[IO, (UUID, Data)]

object Engine:

  case class NoNodesAvailable() extends RuntimeException("no nodes available")

  class Rank(dataId: UUID, hash: Hash, initNodes: List[UUID]):

    private val rank: ArrayBuffer[UUID] = ArrayBuffer.from(initNodes).sortBy(_hash)

    private def _hash(nodeId: UUID): Int = hash.hash(s"$dataId${nodeId}")

    def bestNode: Option[UUID] = rank.lastOption

    def secondBestNode: Option[UUID] = Option.when(rank.size >= 2)(rank(rank.size - 2))

    def addNode(nodeId: UUID): Rank =
      rank += nodeId
      rank.sortInPlaceBy(_hash)
      this

    def removeNode(nodeId: UUID): Rank =
      rank.filterInPlace(_ != nodeId)
      this

    def scores: List[UUID] = rank.toList

  def apply(): Resource[IO, Engine] =
    for
      supervisor <- Supervisor[IO]
      hash <- IO.unit.as(Hash.mmh3()).toResource
      nodesRef <- SignallingRef.of[IO, ListMap[UUID, Node]](ListMap.empty).toResource
      scoresRef <- SignallingRef.of[IO, Map[UUID, Rank]](Map.empty).toResource
      fibers <- Ref.of[IO, Map[UUID, Fiber[IO, Throwable, Unit]]](Map.empty).toResource
      updateSig <- SignallingRef.of[IO, Option[Data]](None).toResource
    yield new Engine:

      def stream: fs2.Stream[IO, ListMap[UUID, Node]] =
        updateSig.discrete.switchMap(_ => nodesRef.discrete)

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
            val rank = new Rank(data.id, hash, currNodes)
            scoresRef.update(_ + (data.id -> rank)) *> IO.pure(rank)
          .flatMap: rank =>
            rank.bestNode.foldMapM: node =>
              nodeWithId(node).flatMap(_.foldMapM(_.add(data)))
          .flatMap: _ =>
            updateSig.set(Some(data))

      def nodeWithId(id: UUID): IO[Option[Node]] = nodesRef.get.map(_.get(id))

      def createNode: IO[Unit] =
        IO.randomUUID.flatMap: nodeId =>
          supervisor.supervise:
            Node.resource(nodeId).use: node =>
              nodesRef.update(_ + (nodeId -> node)) *>
                IO.never.as(())
          .flatMap: fib =>
            fibers.update(_ + (nodeId -> fib))
          .flatMap: _ =>
            scoresRef.get.flatMap:
              _.toList.traverse: (dataId, rank) =>
                IO(rank.addNode(nodeId))
                  .flatTap: _ =>
                    scoresRef.update(_ + (dataId -> rank))
                  .flatTap: newRank =>
                    newRank.secondBestNode.foldMapM: node =>
                      nodeWithId(node).flatMap(_.foldMapM(_.remove(Data(dataId))))
                  .flatMap: newRank =>
                    newRank.bestNode.foldMapM: node =>
                      nodeWithId(node).flatMap(_.foldMapM(_.add(Data(dataId))))
                  .flatMap: _ =>
                    updateSig.set(Some(Data(dataId)))
              .void

      def removeNode(nodeId: UUID): IO[Unit] =
        scoresRef.get.flatMap:
          _.toList.traverse: (dataId, rank) =>
            IO(rank.removeNode(nodeId))
              .flatMap: rank =>
                if rank.scores.isEmpty then
                  scoresRef.update(_ - dataId)
                else
                  scoresRef.update(_ + (dataId -> rank))
          .void
        .flatMap: _ =>
          nodeWithId(nodeId).flatMap:
            _.foldMapM: node =>
              fs2.Stream.evalSeq(node.snapshot)
                .parEvalMapUnbounded: data =>
                  scoresRef.get.flatMap(_.get(data.id).foldMapM: rank =>
                    rank.bestNode.foldMapM: node =>
                      nodeWithId(node).flatMap(_.foldMapM(_.add(data))))
                .compile
                .drain
        .flatMap: _ =>
          nodesRef.update(_ - nodeId)
        .flatMap: _ =>
          fibers.get.flatMap(_.get(nodeId).foldMapM(_.cancel))
        .flatMap: _ =>
          IO.println(s"Node $nodeId is removed")
