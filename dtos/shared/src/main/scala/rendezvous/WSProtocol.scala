package rendezvous.dtos

import io.circe.Codec
import io.circe.generic.semiauto.*

import java.util.UUID

object WSProtocol:

  enum Client:
    case Ping
    case AddNode(timeToLive: Option[Long]) // in seconds
    case RemoveNode(id: UUID)
    case AddData

  object Client:
    given Codec[Client] = deriveCodec[Client]

  enum Server:
    case Pong
    case Nodes(nodes: List[(UUID, List[UUID])])
    case Update(nodeId: UUID, data: UUID)
    case NodeAdded(nodeId: UUID)
    case DataAdded(dataId: UUID)
    case NodeRemoved(nodeId: UUID)
    case NoNodesAvailable

  object Server:
    given Codec[Server] = deriveCodec[Server]
