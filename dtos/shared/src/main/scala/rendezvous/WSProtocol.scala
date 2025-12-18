package rendezvous.dtos

import io.circe.derivation.Configuration
import io.circe.derivation.ConfiguredCodec

import java.util.UUID

object WSProtocol:

  object Client:
    given Configuration = Configuration.default.withDiscriminator("type")

  enum Client derives ConfiguredCodec:
    case Ping
    case AddNode
    case RemoveNode(node: UUID)
    case AddTask

  object Server:
    given Configuration = Configuration.default.withDiscriminator("type")

  enum Server derives ConfiguredCodec:
    case Pong
    case Nodes(nodes: List[(UUID, List[UUID])])
    case Update(node: UUID, task: UUID)
    case NodeAdded(node: UUID)
    case TaskAdded(task: UUID)
    case NodeRemoved(node: UUID)
    case Ttds(ttds: Map[UUID, Long])
    case NoNodesAvailable
