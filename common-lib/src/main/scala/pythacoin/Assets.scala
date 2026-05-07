package pythacoin

import scalus.cardano.ledger.AssetName
import scalus.uplc.builtin.ByteString.utf8

/** Off-chain canonical names for the assets minted under the CDP policy. The on-chain
  * `CdpValidator` uses the literal `"PUSD"` string directly; everything off-chain should refer to
  * `Assets.Pusd` so renaming or future multi-asset support has a single editable source.
  */
object Assets {
    val Pusd: AssetName = AssetName(utf8"PUSD")
}
