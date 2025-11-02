package dtos

import io.circe.Codec
import io.circe.generic.semiauto.*

object WSProtocol:

  enum Client:
    case Ping
    case AddNode
    case AddInstrument

  object Client:
    given Codec[Client] = deriveCodec[Client]

  enum Server:
    case Pong

  object Server:
    given Codec[Server] = deriveCodec[Server]
