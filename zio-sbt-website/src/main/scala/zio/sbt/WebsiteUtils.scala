/*
 * Copyright 2022 dev.zio
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

import io.circe.syntax.*
import io.circe.yaml.Printer.{LineBreak, YamlVersion}
import sbt.File

import zio.*
import zio.sbt.githubactions.*

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

  def generateReadme(sourcePath: String): Task[Unit] =
    for {
      template    <- readFile("README.template.md")
      mainContent <- readFile(sourcePath).map(md => removeYamlHeader(md))
      comment =
        """|[//]: # (This file was autogenerated using `zio-sbt-website` plugin via `sbt generateReadme` command.)
           |[//]: # (So please do not edit it manually. Instead, edit `README.template.md` file. This command will replace any)
           |[//]: # ({{ main_content }} template tag inside the `README.template.md` file with the main content of the)
           |[//]: # ("docs/index.md" file.)
           |""".stripMargin
      readme <- ZIO.succeed(comment + '\n' + template.replaceFirst("\\{\\{.*main_content.*}}", mainContent))
      _ <- ZIO.attemptBlocking(
             Files.write(
               Paths.get("README.md"),
               readme.getBytes(StandardCharsets.UTF_8)
             )
           )
    } yield ()

  @nowarn("msg=detected an interpolated expression")
  def websiteWorkflow: String =
    io.circe.yaml
      .Printer(
        preserveOrder = true,
        dropNullKeys = true,
        splitLines = true,
        lineBreak = LineBreak.Unix,
        version = YamlVersion.Auto
      )
      .pretty(
        Workflow(
          name = "Website",
          triggers = Seq(
            Trigger.Release(Seq("published")),
            Trigger.Push(branches = Seq(Branch.Named("main")))
          ),
          jobs = Seq(
            Job(
              id = "publish-docs",
              name = "Publish Docs",
              condition = Some(
                Condition.Expression("github.event_name == 'release'") &&
                  Condition.Expression("github.event.action == 'published'")
              ),
              steps = Seq(
                Step.StepSequence(
                  Seq(
                    Step.SingleStep(
                      name = "Git Checkout",
                      uses = Some(ActionRef("actions/checkout@v3.1.0")),
                      parameters = Map("fetch-depth" -> "0".asJson)
                    ),
                    Step.SingleStep(
                      name = "Setup Scala",
                      uses = Some(ActionRef("actions/setup-java@v3.6.0")),
                      parameters = Map(
                        "distribution" -> "temurin".asJson,
                        "java-version" -> 17.asJson,
                        "check-latest" -> true.asJson
                      )
                    ),
                    Step.SingleStep(
                      name = "Setup NodeJs",
                      uses = Some(ActionRef("actions/setup-node@v3")),
                      parameters = Map(
                        "node-version" -> "16.x".asJson,
                        "registry-url" -> "https://registry.npmjs.org".asJson
                      )
                    ),
                    Step.SingleStep(
                      name = "Publish Docs to NPM Registry",
                      run = Some("sbt docs/publishToNpm"),
                      env = Map(
                        "NODE_AUTH_TOKEN" -> "${{ secrets.NPM_TOKEN }}"
                      )
                    )
                  )
                )
              )
            ),
            Job(
              id = "generate-readme",
              name = "Generate README",
              steps = Seq(
                Step.SingleStep(
                  name = "Git Checkout",
                  uses = Some(ActionRef("actions/checkout@v3.1.0")),
                  parameters = Map(
                    "ref"         -> "${{ github.head_ref }}".asJson,
                    "fetch-depth" -> "0".asJson
                  )
                ),
                Step.SingleStep(
                  name = "Setup Scala",
                  uses = Some(ActionRef("actions/setup-java@v3.6.0")),
                  parameters = Map(
                    "distribution" -> "temurin".asJson,
                    "java-version" -> 17.asJson,
                    "check-latest" -> true.asJson
                  )
                ),
                Step.SingleStep(
                  name = "Generate Readme",
                  run = Some("sbt docs/generateReadme")
                ),
                Step.SingleStep(
                  name = "Commit Changes",
                  run = Some("""|git config --local user.email "github-actions[bot]@users.noreply.github.com"
                                |git config --local user.name "github-actions[bot]"
                                |git add README.md
                                |git commit -m "update readme." || echo "No changes to commit"
                                |""".stripMargin)
                ),
                Step.SingleStep(
                  name = "Push Changes",
                  uses = Some(ActionRef("ad-m/github-push-action@master")),
                  parameters = Map(
                    "branch" -> "${{ github.head_ref }}".asJson
                  )
                )
              )
            )
          )
        ).asJson
      )
}
