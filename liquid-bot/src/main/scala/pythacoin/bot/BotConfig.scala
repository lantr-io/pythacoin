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
        def req(name: String): String = System.getenv(name) match
            case null  => sys.error(s"$name environment variable is not set")
            case value => value

        val networkName = sys.env.getOrElse("PYTHACOIN_NETWORK", "preprod").toLowerCase
        val (network, magic) = networkName match
            case "mainnet" => (ScalusNetwork.Mainnet, MainnetMagic)
            case "preprod" => (ScalusNetwork.Testnet, PreprodMagic)
            case other     => sys.error(s"Unsupported PYTHACOIN_NETWORK: $other")

        BotConfig(
          network = network,
          blockfrostApiKey = req("BLOCKFROST_API_KEY"),
          pythPolicyIdHex = req("PYTH_POLICY_ID"),
          pythKey = req("PYTH_KEY"),
          relayHost = sys.env.getOrElse("PYTHACOIN_RELAY_HOST", "preprod-node.play.dev.cardano.org"),
          relayPort = sys.env.getOrElse("PYTHACOIN_RELAY_PORT", "3001").toInt,
          networkMagic = magic,
          appId = sys.env.getOrElse("PYTHACOIN_APP_ID", "io.lantr.pythacoin.bot"),
          walletAddrBech32 = req("PYTHACOIN_BOT_ADDR"),
          signingKeyHex = req("PYTHACOIN_BOT_KEY"),
          verificationKeyHex = req("PYTHACOIN_BOT_VKEY"),
          minLtvBps = sys.env.getOrElse("PYTHACOIN_MIN_LTV_BPS", "9000").toInt,
          // Default 2 ADA — a generous safety margin above typical Plutus tx fees
          // (~0.3-0.7 ADA) plus minUtxo headroom. Operators can tighten when they
          // know their fee profile.
          minProfitLovelace =
              sys.env.getOrElse("PYTHACOIN_MIN_PROFIT_LOVELACE", "2000000").toLong,
          dryRun = sys.env.getOrElse("PYTHACOIN_DRY_RUN", "false").toBoolean
        )
    }
}
