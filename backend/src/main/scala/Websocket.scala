package backend

import cats.effect.IO
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Ping

import scala.concurrent.duration.*

class Websocket() extends Http4sDsl[IO]:

  def apply[I: Decoder, O: Encoder](receiveSend: fs2.Pipe[IO, I, O]) =
    (wsb: WebSocketBuilder2[IO]) =>
      HttpRoutes.of[IO]:
        case GET -> Root =>
          val keepAlive = fs2.Stream.fixedDelay[IO](5.seconds).map(_ => Ping())
          wsb.build: (in: fs2.Stream[IO, WebSocketFrame]) =>
            in
              .mergeHaltL(keepAlive)
              .collect { case WebSocketFrame.Text(msg, _) => decode[I](msg) }
              .collect { case Right(msg) => msg }
              .through(receiveSend)
              .map(msgOut => WebSocketFrame.Text(msgOut.asJson.noSpaces))
