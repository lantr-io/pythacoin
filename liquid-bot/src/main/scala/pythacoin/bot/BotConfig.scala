package pythacoin.bot

import scalus.cardano.address.Network as ScalusNetwork

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
    dryRun: Boolean
)

object BotConfig {

    val PreprodMagic: Long = 1L
    val MainnetMagic: Long = 764824073L

    /** Build a config from the standard env-var surface. Throws on missing required vars. */
    def fromEnv(): BotConfig = {
        def req(name: String): String =
            sys.env.getOrElse(name, sys.error(s"$name environment variable is not set"))
        def opt(name: String, default: String): String = sys.env.getOrElse(name, default)

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
          dryRun = opt("PYTHACOIN_DRY_RUN", "false").toBoolean
        )
    }
}
