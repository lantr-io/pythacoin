package pythacoin

import org.scalatest.Suite
import scalus.cardano.ledger.ScriptHash
import scalus.cardano.wallet.hd.HdAccount
import scalus.crypto.ed25519.given
import scalus.testing.yaci.{YaciConfig, YaciDevKit}
import scalus.uplc.builtin.Data

trait YaciDevKitTest extends YaciDevKit { self: Suite =>

    override protected def yaciConfig: YaciConfig = YaciConfig()

    def createAppCtx(): AppCtx = {
        val context = createTestContext()
        val mnemonic =
            "test test test test test test test test test test test test test test test test test test test test test test test sauce"
        val account = HdAccount.fromMnemonic(mnemonic)
        val pythPolicyId = ScriptHash.fromHex(
          "aabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd"
        )
        val cdpScript = CdpContract(pythPolicyId)
        new AppCtx(
          context.cardanoInfo,
          context.provider,
          account,
          account.signerForUtxos,
          pythPolicyId,
          cdpScript
        )
    }
}
