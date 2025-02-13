/*
 * Copyright 2022-2023 dev.zio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.sbt

import java.nio.file.{Files, Paths}

import scala.annotation.nowarn
import scala.sys.process._

import io.circe.syntax._
import io.circe.yaml.Printer.{LineBreak, YamlVersion}
import sbt.File

import zio._
import zio.sbt.WebsiteUtils.DocsVersioning.SemanticVersioning
import zio.sbt.githubactions._

@nowarn("msg=detected an interpolated expression")
object WebsiteUtils {

  import java.nio.charset.StandardCharsets

  def removeYamlHeader(markdown: String): String =
    markdown
      .split("\n")
      .dropWhile(_ == "---")
      .dropWhile(_ != "---")
      .dropWhile(_ == "---")
      .mkString("\n")

  def readFile(pathname: String): Task[String] =
    ZIO.attemptBlocking {
      val source = scala.io.Source.fromFile(new File(pathname))
      val result = source.getLines().mkString("\n")
      source.close()
      result
    }

  def githubBadge(githubUser: String, githubRepo: String, projectName: String): String = {
    val githubBadge = s"https://img.shields.io/github/stars/$githubUser/$githubRepo?style=social"
    val repoUrl     = s"https://github.com/$githubUser/$githubRepo"
    s"[![$projectName]($githubBadge)]($repoUrl)"
  }

  def javadocBadge(groupId: String, artifactId: String): String = {
    val badge      = s"https://javadoc.io/badge2/$groupId/$artifactId/javadoc.svg"
    val javadocUrl = s"https://javadoc.io/doc/$groupId/$artifactId"
    s"[![javadoc]($badge)]($javadocUrl)"
  }

  def discord =
    "[![Chat on Discord!](https://img.shields.io/discord/629491597070827530?logo=discord)](https://discord.gg/2ccFBr4)"

  def ciBadge(githubUser: String, githubRepo: String, workflowName: String): String = {
    val ci = workflowName.replaceAll(" ", "%20")
    s"![CI Badge](https://github.com/$githubUser/$githubRepo/workflows/$ci/badge.svg)"
  }

  def snapshotBadge(groupId: String, artifact: String): String = {
    val badge =
      s"https://img.shields.io/nexus/s/https/oss.sonatype.org/$groupId/$artifact.svg?label=Sonatype%20Snapshot"
    val link = s"https://oss.sonatype.org/content/repositories/snapshots/${groupId.replace('.', '/')}/$artifact/"
    s"[![Sonatype Snapshots]($badge)]($link)"
  }

  def releaseBadge(groupId: String, artifact: String): String = {
    val badge = s"https://img.shields.io/nexus/r/https/oss.sonatype.org/$groupId/$artifact.svg?label=Sonatype%20Release"
    val link  = s"https://oss.sonatype.org/content/repositories/releases/${groupId.replace('.', '/')}/$artifact/"
    s"[![Sonatype Releases]($badge)]($link)"
  }

  sealed abstract class ProjectStage(val name: String, color: String) {
    val stagePage: String =
      "https://github.com/zio/zio/wiki/Project-Stages"
    def badge: String =
      s"https://img.shields.io/badge/Project%20Stage-${name.replace(" ", "%20") + '-' + color}.svg"
  }
  object ProjectStage {
    final case object ProductionReady extends ProjectStage(name = "Production Ready", "brightgreen")
    final case object Development     extends ProjectStage(name = "Development", "green")
    final case object Experimental    extends ProjectStage(name = "Experimental", "yellowgreen")
    final case object Research        extends ProjectStage(name = "Research", "yellow")
    final case object Concept         extends ProjectStage(name = "Concept", "orange")
    final case object Deprecated      extends ProjectStage(name = "Deprecated", "red")
  }

  def projectStageBadge(stage: ProjectStage): String =
    s"[![${stage.name}](${stage.badge})](${stage.stagePage})"

  def generateProjectBadges(
    projectStage: ProjectStage,
    groupId: String,
    artifactId: String,
    docsArtifactId: String,
    githubUser: String,
    githubRepo: String,
    projectName: String,
    ciWorkflowName: String
  ): String = {
    val stage    = projectStageBadge(projectStage)
    val ci       = ciBadge(githubUser, githubRepo, ciWorkflowName)
    val snapshot = snapshotBadge(groupId, artifactId)
    val github   = githubBadge(githubUser, githubRepo, projectName)

    releaseVersion(_ => ()) match {
      case Some(_) =>
        val release = releaseBadge(groupId, artifactId)
        val javadoc = javadocBadge(groupId, docsArtifactId)
        s"$stage $ci $release $snapshot $javadoc $github"
      case None =>
        s"$stage $ci $snapshot $github"
    }
  }

  def generateReadme(
    projectName: String,
    banner: String,
    introduction: String,
    documentation: String,
    codeOfConduct: String,
    contribution: String,
    support: String,
    license: String,
    acknowledgement: String,
    credits: String,
    maintainers: String
  ): Task[Unit] = {
    val commentSection =
      """|[//]: # (This file was autogenerated using `zio-sbt-website` plugin via `sbt generateReadme` command.)
         |[//]: # (So please do not edit it manually. Instead, change "docs/index.md" file or sbt setting keys)
         |[//]: # (e.g. "readmeDocumentation" and "readmeSupport".)
         |""".stripMargin
    val introductionSection    = s"\n# $projectName\n\n$introduction\n"
    val creditsSection         = if (credits.nonEmpty) s"\n## Credits\n\n$credits\n" else ""
    val supportSection         = s"\n## Support\n\n$support\n"
    val codeOfConductSection   = s"\n## Code of Conduct\n\n$codeOfConduct\n"
    val contributingSection    = s"\n## Contributing\n\n$contribution\n"
    val documentationSection   = s"\n## Documentation\n\n$documentation\n"
    val maintainersSection     = if (maintainers.nonEmpty) s"\n## Maintainers\n\n$maintainers\n" else ""
    val acknowledgementSection = if (acknowledgement.nonEmpty) s"\n## Acknowledgement\n\n$acknowledgement\n" else ""
    val licenseSection         = s"\n## License\n\n$license\n"
    val readme =
      commentSection + banner + introductionSection + documentationSection + contributingSection + codeOfConductSection +
        supportSection + maintainersSection + creditsSection + acknowledgementSection + licenseSection

    ZIO.attemptBlocking {
      Files.write(
        Paths.get("README.md"),
        readme.getBytes(StandardCharsets.UTF_8)
      )
      ()
    }
  }

  abstract class DocsVersioning(val npmCommand: String)
  object DocsVersioning {
    object SemanticVersioning extends DocsVersioning("publishToNpm")
    object HashVersioning     extends DocsVersioning("publishHashverToNpm")
  }

  @nowarn("msg=detected an interpolated expression")
  def websiteWorkflow(
    docsPublishBranch: String,
    scalaVersions: List[String],
    projects: List[String],
    sbtBuildOptions: List[String] = List.empty,
    versioning: DocsVersioning = SemanticVersioning,
    updateReadmeCondition: Option[Condition] = None,
    checkArtifactBuildProcess: Option[Step] = None
  ): String = {
    object Actions {
      val checkout: ActionRef     = ActionRef("actions/checkout@v3.3.0")
      val `setup-java`: ActionRef = ActionRef("actions/setup-java@v3.9.0")
      val `setup-node`: ActionRef = ActionRef("actions/setup-node@v3")
    }

    import Actions._

    object Steps {
      val Checkout: Step.SingleStep = Step.SingleStep(
        name = "Git Checkout",
        uses = Some(checkout),
        parameters = Map("fetch-depth" -> "0".asJson)
      )

      def SetupJava(version: String = "17"): Step.SingleStep = Step.SingleStep(
        name = "Setup Scala",
        uses = Some(`setup-java`),
        parameters = Map(
          "distribution" -> "temurin".asJson,
          "java-version" -> version.asJson,
          "check-latest" -> true.asJson
        )
      )

      val Test: Step.SingleStep =
        Step.SingleStep(
          name = "Test",
          run = Some("sbt 'project ${{ matrix.project }}' '++${{ matrix.scala }}' test")
        )

      val SetupNodeJs: Step.SingleStep = Step.SingleStep(
        name = "Setup NodeJs",
        uses = Some(`setup-node`),
        parameters = Map(
          "node-version" -> "16.x".asJson,
          "registry-url" -> "https://registry.npmjs.org".asJson
        )
      )

      val Release: Step.SingleStep =
        Step.SingleStep(
          name = "Release",
          run = Some("sbt ci-release"),
          env = Map(
            "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
            "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
            "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
            "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
          )
        )

      val Lint: Step.SingleStep = Step.SingleStep(
        name = "Lint",
        run = Some("sbt lint")
      )

      val GenerateReadme: Step.SingleStep = Step.SingleStep(
        name = "Generate Readme",
        run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/generateReadme")
      )

      val CheckWebsiteBuildProcess: Step.SingleStep = Step.SingleStep(
        name = "Check website build process",
        run = Some(s"sbt docs/clean; sbt ${sbtBuildOptions.mkString(" ")} docs/buildWebsite")
      )

      val CheckGithubWorkflow: Step.SingleStep = Step.SingleStep(
        name = "Check if the site workflow is up to date",
        run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/checkGithubWorkflow")
      )

      val CheckReadme: Step.SingleStep = Step.SingleStep(
        name = "Check if the README file is up to date",
        run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/checkReadme")
      )

      val PublishToNpmRegistry: Step.SingleStep =
        Step.SingleStep(
          name = "Publish Docs to NPM Registry",
          run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/${versioning.npmCommand}"),
          env = Map(
            "NODE_AUTH_TOKEN" -> "${{ secrets.NPM_TOKEN }}"
          )
        )
    }

    import Steps._

    io.circe.yaml
      .Printer(
        preserveOrder = true,
        dropNullKeys = true,
        splitLines = false,
        lineBreak = LineBreak.Unix,
        version = YamlVersion.Auto
      )
      .pretty(
        Workflow(
          name = "Website",
          env = Map(
            // JDK_JAVA_OPTIONS is _the_ env. variable to use for modern Java
            "JDK_JAVA_OPTIONS" -> "-XX:+PrintCommandLineFlags -Xmx6G -Xss4M -XX:+UseG1GC",
            // For Java 8 only (sadly, it is not modern enough for JDK_JAVA_OPTIONS)
            "JVM_OPTS"     -> "-XX:+PrintCommandLineFlags -Xmx6G -Xss4M -XX:+UseG1GC",
            "NODE_OPTIONS" -> "--max_old_space_size=6144"
          ),
          triggers = Seq(
            Trigger.WorkflowDispatch(),
            Trigger.Release(Seq("published")),
            Trigger.Push(branches = Seq(Branch.Named(docsPublishBranch))),
            Trigger.PullRequest()
          ),
          jobs = Seq(
            Job(
              id = "build",
              name = "Build",
              steps = Seq(
                Step.StepSequence(
                  checkArtifactBuildProcess match {
                    case Some(artifactBuildProcess) =>
                      Seq(
                        Checkout,
                        SetupJava(),
                        CheckGithubWorkflow,
                        artifactBuildProcess,
                        CheckWebsiteBuildProcess
                      )
                    case None =>
                      Seq(
                        Checkout,
                        SetupJava(),
                        CheckGithubWorkflow,
                        CheckWebsiteBuildProcess
                      )
                  }
                )
              )
            ),
            Job(
              id = "lint",
              name = "Lint",
              steps = Seq(
                Checkout,
                SetupJava(),
                Lint
              )
            ),
            Job(
              id = "test",
              name = "Test",
              strategy = Some(
                Strategy(
                  Map(
                    "java"    -> List("8", "11", "17"),
                    "scala"   -> scalaVersions,
                    "project" -> projects
                  )
                )
              ),
              steps = Seq(
                SetupJava("${{ matrix.java }}"),
                Checkout,
                Test
              )
            ),
            Job(
              id = "release",
              name = "Release",
              need = Seq("build", "lint", "test"),
              condition = Some(
                Condition.Expression("github.event_name != 'pull_request'") &&
                  (Condition.Expression("github.ref == 'refs/heads/main'") ||
                    Condition.Expression("startsWith(github.ref, 'refs/tags/v')"))
              ),
              steps = Seq(
                Checkout,
                SetupJava(),
                Release
              )
            ),
            Job(
              id = "publish-docs",
              name = "Publish Docs",
              need = Seq("release"),
              condition = Some(
                Condition.Expression("github.event_name == 'release'") &&
                  Condition.Expression("github.event.action == 'published'") || Condition.Expression(
                    "github.event_name == 'workflow_dispatch'"
                  )
              ),
              steps = Seq(
                Step.StepSequence(
                  Seq(
                    Checkout,
                    SetupJava(),
                    SetupNodeJs,
                    PublishToNpmRegistry
                  )
                )
              )
            ),
            Job(
              id = "generate-readme",
              name = "Generate README",
              need = Seq("release"),
              condition = updateReadmeCondition orElse Some(
                Condition.Expression("github.event_name == 'push'") ||
                  Condition.Expression("github.event_name == 'release'") &&
                  Condition.Expression("github.event.action == 'published'")
              ),
              steps = Seq(
                Step.SingleStep(
                  name = "Git Checkout",
                  uses = Some(checkout),
                  parameters = Map(
                    "ref"         -> "${{ github.head_ref }}".asJson,
                    "fetch-depth" -> "0".asJson
                  )
                ),
                SetupJava(),
                GenerateReadme,
                Step.SingleStep(
                  name = "Commit Changes",
                  run = Some("""|git config --local user.email "github-actions[bot]@users.noreply.github.com"
                                |git config --local user.name "github-actions[bot]"
                                |git add README.md
                                |git commit -m "Update README.md" || echo "No changes to commit"
                                |""".stripMargin)
                ),
                Step.SingleStep(
                  name = "Create Pull Request",
                  uses = Some(ActionRef("peter-evans/create-pull-request@v4.2.3")),
                  parameters = Map(
                    "title"          -> "Update README.md".asJson,
                    "commit-message" -> "Update README.md".asJson,
                    "branch"         -> "zio-sbt-website/update-readme".asJson,
                    "delete-branch"  -> true.asJson,
                    "body" ->
                      """|Autogenerated changes after running the `sbt docs/generateReadme` command of the [zio-sbt-website](https://zio.dev/zio-sbt) plugin.
                         |
                         |I will automatically update the README.md file whenever there is new change for README.md, e.g.
                         |  - After each release, I will update the version in the installation section.
                         |  - After any changes to the "docs/index.md" file, I will update the README.md file accordingly.""".stripMargin.asJson
                  )
                )
              )
            )
          )
        ).asJson
      )
  }

  def releaseVersion(logger: String => Unit): Option[String] =
    try "git tag --sort=committerdate".!!.split("\n").filter(_.startsWith("v")).lastOption.map(_.tail)
    catch {
      case _: Exception =>
        logger(
          s"Could not determine release version from git tags, will return 'None' instead.  This is most likely a result of this project not having a git repo initialized.  See previous log messages for more detail."
        )
        None
    }
}
