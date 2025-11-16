package scripts

import bleep.model

object projectsToPublish {
  // will publish these with dependencies
  def include(crossName: model.CrossProjectName): Boolean =
    crossName.name.value match {
      case "schemas"       => true
      case "server"        => true
      case "server-http4s" => true
      case _               => false
    }
}
