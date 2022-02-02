package sttp.tapir.client.armeria

import sttp.tapir.{Defaults, TapirFile}

case class ArmeriaClientOptions(createFile: () => TapirFile)

object ArmeriaClientOptions {
  val default: ArmeriaClientOptions = ArmeriaClientOptions(Defaults.createTempFile)
}
