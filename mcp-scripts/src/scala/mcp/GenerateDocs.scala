package mcp

import bleep.*
import bleep.plugin.dynver.DynVerPlugin
import bleep.plugin.mdoc.{DocusaurusPlugin, MdocPlugin}
import coursier.core.{ModuleName, Organization}

import java.io.File
import java.nio.file.Path
import scala.collection.immutable.SortedSet

object GenerateDocs extends BleepScript("Docs") {

  override def run(started: Started, commands: Commands, args: List[String]): Unit = {
    val scriptsProject = model.CrossProjectName(model.ProjectName("mcp-scripts"), crossId = None)
    commands.compile(List(scriptsProject))

    // mdoc 2.8 ships with scala3-compiler 3.3.7 which can't handle our code.
    // We force the compiler to match the project's Scala version from bleep.yaml.
    val explodedProject = started.build.explodedProjects(scriptsProject)
    val projectScalaVersion = explodedProject.scala.flatMap(_.version).map(_.scalaVersion).getOrElse("3.7.3")
    val mdocScalaVersionCombo = model.VersionCombo.Jvm(model.VersionScala(projectScalaVersion))

    val dynVer = new DynVerPlugin(baseDirectory = started.buildPaths.buildDir.toFile, dynverSonatypeSnapshots = true)

    val mdoc = new MdocPlugin(started, scriptsProject, mdocVersion = "2.8.2") {
      override def mdocIn: Path = started.buildPaths.buildDir / "site-docs"

      override def mdocOut: Path = started.buildPaths.buildDir / "site" / "docs"

      override def mdocVariables: Map[String, String] =
        Map("VERSION" -> dynVer.version)

      override def getVersionCombo(explodedProject: model.Project): model.VersionCombo.Scala =
        mdocScalaVersionCombo

      override def getJars(scalaCombo: model.VersionCombo.Scala, deps: model.Dep*): List[Path] = {
        val scala3Compiler = model.Dep.ScalaDependency(
          Organization("org.scala-lang"),
          ModuleName("scala3-compiler"),
          projectScalaVersion,
          fullCrossVersion = false,
          for3Use213 = false
        )
        val allDeps = deps.toSet + scala3Compiler
        started.resolver
          .force(allDeps, scalaCombo, libraryVersionSchemes = SortedSet.empty, "booting mdoc", model.IgnoreEvictionErrors.No)
          .jars
      }
    }

    val nodeBinPath = started.pre.fetchNode("24.12.0").getParent
    started.logger.withContext("nodeBinPath", nodeBinPath).info("Using node")

    val env = sys.env.collect {
      case x @ ("SSH_AUTH_SOCK", _) => x
      case ("PATH", value)          => "PATH" -> s"$nodeBinPath${File.pathSeparator}$value"
    }.toList

    val docusaurus = new DocusaurusPlugin(
      website = started.buildPaths.buildDir / "site",
      mdoc = mdoc,
      docusaurusProjectName = "site",
      env = env,
      logger = started.logger,
      isDocusaurus2 = true
    )

    args.headOption match {
      case Some("mdoc") =>
        mdoc.mdoc(args = Nil)
      case Some("dev") =>
        docusaurus.dev(using started.executionContext)
      case Some("deploy") =>
        docusaurus.docusaurusPublishGhpages(mdocArgs = Nil)
      case Some(other) =>
        sys.error(s"Expected argument to be dev or deploy, not $other")
      case None =>
        val path = docusaurus.doc(mdocArgs = Nil)
        started.logger.info(s"Created documentation at $path")
    }
  }
}
