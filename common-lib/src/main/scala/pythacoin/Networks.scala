package pythacoin

import scalus.cardano.address.Network as ScalusNetwork

/** Which Cardano testnet (or mainnet) we're talking to. Drives the Blockfrost
  * endpoint, network magic, and a couple of relay defaults.
  *
  * Scalus's own `Network` enum collapses preview + preprod into `Testnet`, so
  * we keep a separate enum to disambiguate. Magic values match the canonical
  * `scalus.cardano.network.NetworkMagic` constants (Mainnet=764824073,
  * Preprod=1, Preview=2).
  */
enum CardanoNet(val scalusNetwork: ScalusNetwork, val magic: Long) {
    case Mainnet extends CardanoNet(ScalusNetwork.Mainnet, 764824073L)
    case Preprod extends CardanoNet(ScalusNetwork.Testnet, 1L)
    case Preview extends CardanoNet(ScalusNetwork.Testnet, 2L)
}

object CardanoNet {

    /** Parse a network name (case-insensitive) against the enum's own
      * `toString`s — adding a new case automatically extends the parser.
      */
    def parse(s: String): CardanoNet =
        values.find(_.toString.equalsIgnoreCase(s)).getOrElse(
          sys.error(s"Unknown network: '$s' (expected: ${values.map(_.toString.toLowerCase).mkString("|")})")
        )
}
