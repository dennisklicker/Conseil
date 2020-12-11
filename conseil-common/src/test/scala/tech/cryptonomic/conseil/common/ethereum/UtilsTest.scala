package tech.cryptonomic.conseil.common.ethereum

import tech.cryptonomic.conseil.common.testkit.ConseilSpec

class UtilsTest extends ConseilSpec {
  "Utils" should {
      "decode hex string" in {
        Utils.hexToString("0x313233") shouldBe "123"
      }

      "create sha-3 signature for string value" in {
        Utils.keccak("totalSupply()") shouldBe "18160DDD"
      }

      "convert hex string to big decimal" in {
        Utils.hexStringToBigDecimal("0x1") shouldBe 0x1
      }
    }
}
