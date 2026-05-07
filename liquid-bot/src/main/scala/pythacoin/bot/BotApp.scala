package pythacoin.bot

import ox.{ExitCode, Ox, OxApp}

/** Entry point for the liquidation bot. Command-line parsing lives in [[BotCli]]; the running bot
  * lives in [[Bot]]. This object is the lifecycle outline.
  *
  * Extending `OxApp` gives us a `supervised` scope around `run` (so `useCloseableInScope` inside
  * `Bot` works), a JVM shutdown hook that cancels the main fork on SIGINT/SIGTERM, and configurable
  * exception callbacks via `OxApp.Settings`.
  */
object BotApp extends OxApp {

    override def run(args: Vector[String])(using Ox): ExitCode =
        BotCli.resolveConfig(args) match
            case Left(help) => println(help); ExitCode.Failure(2)
            case Right(cfg) => Bot(cfg).run(); ExitCode.Success

}
