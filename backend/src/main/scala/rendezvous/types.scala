package rendezvous.backend

import java.util.UUID

object types:

  opaque type TaskID = UUID

  object TaskID:
    def apply(uuid: UUID): TaskID = uuid

    extension (taskId: TaskID)
      def value: UUID = taskId

  opaque type NodeID = UUID

  object NodeID:
    def apply(uuid: UUID): NodeID = uuid

    extension (nodeId: NodeID)
      def value: UUID = nodeId

  trait Syntax:

    extension (uuid: UUID)
      def taskId: TaskID = TaskID(uuid)
      def nodeId: NodeID = NodeID(uuid)
