package pythacoin

import scalus.compiler.Options
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.ByteString
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toData
import pythacoin.onchain.{CdpParams, CdpValidator}

object CdpContract {
    given Options = Options.release

    lazy val base: PlutusV3[Data => Data => Unit] =
        PlutusV3.compile(CdpValidator.validate)

    def apply(pythPolicyId: ByteString): PlutusV3[Data => Unit] =
        base.apply(CdpParams(pythPolicyId).toData)

    def withErrorTraces(pythPolicyId: ByteString): PlutusV3[Data => Unit] =
        base.withErrorTraces.apply(CdpParams(pythPolicyId).toData)
}
