package backend

import scala.util.hashing.MurmurHash3

trait Hash:

  def hash(str: String): Int

object Hash:

  def apply(): Hash = mmh3()

  def mmh3(): Hash = MurmurHash3.stringHash(_)
