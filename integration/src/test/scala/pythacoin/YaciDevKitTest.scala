package pythacoin

import org.scalatest.Suite
import scalus.cardano.ledger.ScriptHash
import scalus.testing.yaci.{YaciConfig, YaciDevKit}

trait YaciDevKitTest extends YaciDevKit { self: Suite =>

    override protected def yaciConfig: YaciConfig = YaciConfig()

    def createAppCtx(): AppCtx = {
        val context = createTestContext()
        val pythPolicyId = ScriptHash.fromHex(
          "aabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd"
        )
        val cdpScript = CdpContract(pythPolicyId)
        // Yaci-Store binds inside the container on 8080 but the host port is
        // randomised by Testcontainers — ask the container for the actual URL.
        val yaciStoreUrl = container.getYaciStoreApiUrl().stripSuffix("/")
        new AppCtx(
          context.cardanoInfo, context.provider,
          "", yaciStoreUrl,
          pythPolicyId, "", cdpScript
        )
    }
}
