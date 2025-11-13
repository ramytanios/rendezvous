package rendezvous.backend

import scala.util.hashing.MurmurHash3

trait Hash:

  def hash(s: String): Int

  final def normalized(s: String): Double = // in (0, 1)
    val unsigned = hash(s) & 0xffffffffL
    unsigned.toDouble / (1L << 32)

object Hash:

  def apply(): Hash = mmh3()

  def mmh3(): Hash = MurmurHash3.stringHash(_)
