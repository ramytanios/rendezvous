package rendezvous.backend

import cats.effect.IO
import cats.implicits.*
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import fs2.io.net.Network
import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.websocket.WebSocketBuilder2
import rendezvous.backend.Websocket
import rendezvous.dtos

final class Server(
    host: String,
    port: Int,
    receiveSend: fs2.Pipe[IO, dtos.WSProtocol.Client, dtos.WSProtocol.Server]
) extends Http4sDsl[IO]:

  case class HttpServerException(msg: String) extends RuntimeException(msg)

  private def constructRoutes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    val route =
      new Websocket().apply[dtos.WSProtocol.Client, dtos.WSProtocol.Server](receiveSend)(wsb)
    Router("/api/ws" -> route)

  def run: IO[Unit] =
    for
      host <- Host
        .fromString(host)
        .liftTo[IO](HttpServerException(s"Invalid host $host"))
      port <- Port
        .fromInt(port)
        .liftTo[IO](HttpServerException(s"Invalid port $port"))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(constructRoutes(_).orNotFound)
        .withMaxConnections(256)
        .build
        .evalTap: _ =>
          IO.println(s"server listening on port $port")
        .use: _ =>
          IO.never
    yield ()
