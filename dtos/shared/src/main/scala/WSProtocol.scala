package dtos

import io.circe.Codec
import io.circe.generic.semiauto.*
import java.util.UUID

object WSProtocol:

  enum Client:
    case Ping
    case AddNode
    case AddInstrument

  object Client:
    given Codec[Client] = deriveCodec[Client]

  enum Server:
    case Pong
    case Nodes(nodes: Map[UUID, Seq[UUID]])

  object Server:
    given Codec[Server] = deriveCodec[Server]
