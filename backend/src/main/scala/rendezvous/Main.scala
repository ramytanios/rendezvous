package rendezvous.backend

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.std.Queue
import cats.syntax.all.*
import rendezvous.dtos

object Main extends IOApp.Simple:

  case class HttpServerException(msg: String) extends RuntimeException(msg)

  def receiveSend(engine: Engine): fs2.Pipe[IO, dtos.WSProtocol.Client, dtos.WSProtocol.Server] =

    (in: fs2.Stream[IO, dtos.WSProtocol.Client]) =>
      fs2.Stream
        .eval(Queue.unbounded[IO, dtos.WSProtocol.Server])
        .flatMap: outQ =>
          fs2.Stream.fromQueueUnterminated(outQ)
            .concurrently:
              in.evalMap:
                case dtos.WSProtocol.Client.Ping =>
                  outQ.offer(dtos.WSProtocol.Server.Pong)
                case dtos.WSProtocol.Client.AddNode =>
                  engine.createNode.flatMap: nodeId =>
                    outQ.offer(dtos.WSProtocol.Server.NodeAdded(nodeId))
                case dtos.WSProtocol.Client.AddData =>
                  IO.randomUUID.flatTap: dataId =>
                    engine.addData(Data(dataId))
                  .flatMap: dataId =>
                    outQ.offer(dtos.WSProtocol.Server.DataAdded(dataId))
                  .handleErrorWith:
                    case Engine.NoNodesAvailable() =>
                      outQ.offer(dtos.WSProtocol.Server.NoNodesAvailable)
                case dtos.WSProtocol.Client.RemoveNode(nodeId) =>
                  engine.removeNode(nodeId) *>
                    outQ.offer(dtos.WSProtocol.Server.NodeRemoved(nodeId))
            .concurrently:
              engine
                .stream
                .evalMap:
                  _.toList
                    .traverse: (uuid, node) =>
                      node.snapshot.map(data => uuid -> data.map(_.id))
                .changes
                .evalMap: data =>
                  outQ.offer(dtos.WSProtocol.Server.Nodes(data))
            .concurrently:
              engine
                .updates
                .evalMap: (nodeId, data) =>
                  outQ.offer(dtos.WSProtocol.Server.Update(nodeId, data.id))

  override def run: IO[Unit] =
    Engine.resource().use: engine =>
      new Server("127.0.0.1", 8090, receiveSend(engine)).run
