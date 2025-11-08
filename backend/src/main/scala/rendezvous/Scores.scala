package rendezvous.backend

import java.util.UUID
import scala.collection.mutable.ArrayBuffer

class Scores(dataId: UUID, hash: Hash, initNodes: List[UUID]):

  private val _scores: ArrayBuffer[UUID] = ArrayBuffer.from(initNodes).sortBy(_hash)

  private def _hash(nodeId: UUID): Int = hash.hash(s"$dataId${nodeId}")

  def bestNode: Option[UUID] = _scores.lastOption

  def secondBestNode: Option[UUID] = Option.when(_scores.size >= 2)(_scores(_scores.size - 2))

  def addNode(nodeId: UUID): Scores =
    _scores += nodeId
    _scores.sortInPlaceBy(_hash)
    this

  def removeNode(nodeId: UUID): Scores =
    _scores.filterInPlace(_ != nodeId)
    this

  def scores: List[UUID] = _scores.toList
