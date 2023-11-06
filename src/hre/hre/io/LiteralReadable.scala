package hre.io

import java.io.{Reader, StringReader}

case class LiteralReadable(fileName: String, data: String) extends Readable {
  override def isRereadable: Boolean = true

  override protected def getReader: Reader =
    new StringReader(data)
}
