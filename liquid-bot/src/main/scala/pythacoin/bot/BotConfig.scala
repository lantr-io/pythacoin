package pythacoin.bot

import scalus.cardano.address.Network as ScalusNetwork

/** Pyth Lazer push-channel rate. The wire-format string is what the WS
  * subscribe message expects; an unknown env value fails fast at startup
  * rather than at the first WS frame.
  */
enum PythChannel(val wireName: String) {
    case FixedRate200ms extends PythChannel("fixed_rate@200ms")
    case FixedRate50ms  extends PythChannel("fixed_rate@50ms")
    case RealTime       extends PythChannel("real_time")
}

object PythChannel {
    def parse(s: String): PythChannel = values.find(_.wireName == s).getOrElse(
      sys.error(s"Unsupported PYTHACOIN_PYTH_CHANNEL: '$s'. Expected one of ${values.map(_.wireName).mkString(", ")}.")
    )
}

/** Static configuration for the liquidation bot. Built once at startup from
  * environment variables / CLI flags; never mutated afterwards.
  *
  * The bot reads the chain via the embedded scalus-node (N2N to a relay, with
  * Blockfrost as the fall-through backup for snapshot queries and submission)
  * and signs liquidation transactions with `signingKey`.
  */
final case class BotConfig(
    network: ScalusNetwork,
    blockfrostApiKey: String,
    pythPolicyIdHex: String,
    pythKey: String,
    relayHost: String,
    relayPort: Int,
    networkMagic: Long,
    appId: String,
    walletAddrBech32: String,
    signingKeyHex: String,
    verificationKeyHex: String,
    minLtvBps: Int,
    minProfitLovelace: Long,
    dryRun: Boolean,
    pythWsUrl: String,
    pythChannel: PythChannel,
    priceMaxAgeSeconds: Long
)

object BotConfig {

    val PreprodMagic: Long = 1L
    val MainnetMagic: Long = 764824073L

    /** Build a config from the standard env-var surface. Throws on missing required vars. */
    def fromEnv(): BotConfig = fromMap(sys.env)

    /** Build a config from an arbitrary key/value map. Tests use this to feed
      * a parsed `.env` file (or any in-memory overrides) without touching the
      * JVM's real environment.
      */
    def fromMap(env: Map[String, String]): BotConfig = {
        def req(name: String): String =
            env.getOrElse(name, sys.error(s"$name environment variable is not set"))
        def opt(name: String, default: String): String = env.getOrElse(name, default)

        val (network, magic) = opt("PYTHACOIN_NETWORK", "preprod").toLowerCase match
            case "mainnet" => (ScalusNetwork.Mainnet, MainnetMagic)
            case "preprod" => (ScalusNetwork.Testnet, PreprodMagic)
            case other     => sys.error(s"Unsupported PYTHACOIN_NETWORK: $other")

        BotConfig(
          network = network,
          blockfrostApiKey = req("BLOCKFROST_API_KEY"),
          pythPolicyIdHex = req("PYTH_POLICY_ID"),
          pythKey = req("PYTH_KEY"),
          relayHost = opt("PYTHACOIN_RELAY_HOST", "preprod-node.play.dev.cardano.org"),
          relayPort = opt("PYTHACOIN_RELAY_PORT", "3001").toInt,
          networkMagic = magic,
          appId = opt("PYTHACOIN_APP_ID", "io.lantr.pythacoin.bot"),
          walletAddrBech32 = req("PYTHACOIN_BOT_ADDR"),
          signingKeyHex = req("PYTHACOIN_BOT_KEY"),
          verificationKeyHex = req("PYTHACOIN_BOT_VKEY"),
          minLtvBps = opt("PYTHACOIN_MIN_LTV_BPS", "9000").toInt,
          // 2 ADA: comfortable margin over typical Plutus tx fee (~0.3–0.7 ADA)
          // plus minUtxo headroom. Tighten once observed fees are known.
          minProfitLovelace = opt("PYTHACOIN_MIN_PROFIT_LOVELACE", "2000000").toLong,
          dryRun = opt("PYTHACOIN_DRY_RUN", "false").toBoolean,
          pythWsUrl = opt("PYTHACOIN_PYTH_WS_URL", "wss://pyth-lazer-0.dourolabs.app/v1/stream"),
          pythChannel = PythChannel.parse(opt("PYTHACOIN_PYTH_CHANNEL", "fixed_rate@200ms")),
          // 60 s is well under the validator's ±600 s validity window so a tx
          // built from a still-fresh cached price is comfortably accepted.
          priceMaxAgeSeconds = opt("PYTHACOIN_PRICE_MAX_AGE_SECONDS", "60").toLong
        )
    }
}
