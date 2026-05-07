package pythacoin.bot

import com.monovore.decline.{Command, Opts}

/** Command-line surface for [[Bot]]. Two subcommands:
  *   - `dry-run` — connect, follow the chain, log candidate liquidations, never submit.
  *   - `start` — same, plus actually submit liquidation transactions.
  *
  * Kept separate from `Bot` so the entry point reads as a lifecycle outline rather than a decline
  * tutorial. The `--help` text and per-flag wiring are presentation noise; the bot's runtime story
  * is in `Bot`.
  */
object BotCli {

    /** What the user asked us to do. */
    enum Mode {
        case DryRun, Start
    }

    private val modeOpts: Opts[Mode] = {
        val dryRun = Opts.subcommand("dry-run", "Follow the chain and log candidate liquidations")(
          Opts(Mode.DryRun)
        )
        val start = Opts.subcommand("start", "Run the liquidation bot")(Opts(Mode.Start))
        dryRun orElse start
    }

    private val command: Command[Mode] =
        Command(name = "pythacoin-bot", header = "Pythacoin liquidation keeper")(modeOpts)

    /** Parse `args`, build a [[BotConfig]] from the environment, and apply the CLI-level dry-run
      * override (`dry-run` subcommand forces `dryRun = true` regardless of what env said, but
      * `PYTHACOIN_DRY_RUN=true` always wins).
      *
      * `Left(helpText)` on parse failure or `--help`; the caller prints it and returns a non-zero
      * exit code.
      */
    def resolveConfig(args: Vector[String]): Either[String, BotConfig] =
        command.parse(args).left.map(_.toString).map { mode =>
            val envCfg = BotConfig.fromEnv()
            envCfg.copy(dryRun = (mode == Mode.DryRun) || envCfg.dryRun)
        }
}
