package rendezvous.dtos

import io.circe.Codec
import io.circe.generic.semiauto.*

import java.util.UUID

object WSProtocol:

  enum Client:
    case Ping
    case AddNode
    case RemoveNode(id: UUID)
    case AddTask

  object Client:
    given Codec[Client] = deriveCodec[Client]

  enum Server:
    case Pong
    case Nodes(nodes: List[(UUID, List[UUID])])
    case Update(nodeId: UUID, taskId: UUID)
    case NodeAdded(nodeId: UUID)
    case TaskAdded(taskId: UUID)
    case NodeRemoved(nodeId: UUID)
    case Ttds(ttds: Map[UUID, Long])
    case NoNodesAvailable

  object Server:
    given Codec[Server] = deriveCodec[Server]
