package tech.cryptonomic.conseil.api.routes.platform.discovery

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.scalamock.scalatest.MockFactory
import io.circe._
import tech.cryptonomic.conseil.api.metadata.{AttributeValuesCacheConfiguration, MetadataService, UnitTransformation}
import tech.cryptonomic.conseil.common.config.Platforms.{
  PlatformsConfiguration,
  TezosConfiguration,
  TezosNodeConfiguration
}
import tech.cryptonomic.conseil.common.testkit.ConseilSpec
import tech.cryptonomic.conseil.common.config.Types.PlatformName
import tech.cryptonomic.conseil.common.config._
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.DataType.Int
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.KeyType.NonKey
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.{Attribute, Entity}
import tech.cryptonomic.conseil.common.metadata.{EntityPath, NetworkPath, PlatformPath}
import tech.cryptonomic.conseil.common.util.JsonUtil.toListOfMaps

class PlatformDiscoveryTest extends ConseilSpec with ScalatestRouteTest with MockFactory {

  "The platform discovery route" should {

      val platformDiscoveryOperations = new TestPlatformDiscoveryOperations
      val cacheOverrides = stub[AttributeValuesCacheConfiguration]

      val dbCfg = ConfigFactory.parseString("""
                                                    |    db {
                                                    |      dataSourceClass: "org.postgresql.ds.PGSimpleDataSource"
                                                    |      properties {
                                                    |        user: "foo"
                                                    |        password: "bar"
                                                    |        url: "jdbc:postgresql://localhost:5432/postgres"
                                                    |      }
                                                    |      numThreads: 10
                                                    |      maxConnections: 10
                                                    |    }
        """.stripMargin)

      val sut = (metadataOverridesConfiguration: Map[PlatformName, PlatformConfiguration]) =>
        PlatformDiscovery(
          new MetadataService(
            PlatformsConfiguration(
              List(
                TezosConfiguration(
                  "mainnet",
                  enabled = true,
                  TezosNodeConfiguration("tezos-host", 123, "https://"),
                  BigDecimal.decimal(8000),
                  dbCfg,
                  None
                )
              )
            ),
            new UnitTransformation(MetadataConfiguration(metadataOverridesConfiguration)),
            cacheOverrides,
            platformDiscoveryOperations
          )
        ).route

      val testNetworkPath = NetworkPath("mainnet", PlatformPath("tezos"))
      val testEntityPath = EntityPath("entity", testNetworkPath)

      "expose an endpoint to get the list of supported platforms" in {
        // given
        val matadataOverridesConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(true)))

        // when
        Get("/v2/metadata/platforms") ~> addHeader("apiKey", "hooman") ~> sut(matadataOverridesConfiguration) ~> check {

          // then
          status shouldEqual StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`
          val result: List[Map[String, String]] = toListOfMaps[String](responseAs[String]).get
          result.head("name") shouldBe "tezos"
          result.head("displayName") shouldBe "Tezos"
        }
      }

      "should filter out hidden platforms" in {
        // given
        val overridesConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(false)))

        // when
        Get("/v2/metadata/platforms") ~> addHeader("apiKey", "hooman") ~> sut(overridesConfiguration) ~> check {

          // then
          status shouldEqual StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`
          val result: List[Map[String, String]] = toListOfMaps[String](responseAs[String]).get
          result.size shouldBe 0
        }
      }

      "should rename platform's display name and description" in {
        // given
        val overridesConfiguration =
          Map("tezos" -> PlatformConfiguration(Some("overwritten-name"), Some(true), Some("description")))

        // when
        Get("/v2/metadata/platforms") ~> addHeader("apiKey", "hooman") ~> sut(overridesConfiguration) ~> check {

          // then
          status shouldEqual StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`
          val result: List[Map[String, String]] = toListOfMaps[String](responseAs[String]).get
          result.head("displayName") shouldBe "overwritten-name"
          result.head("description") shouldBe "description"
        }
      }

      "expose an endpoint to get the list of supported networks" in {
        // given
        val overridesConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(None, Some(true))
                )
              )
        )

        // when
        Get("/v2/metadata/tezos/networks") ~> addHeader("apiKey", "hooman") ~> sut(overridesConfiguration) ~> check {

          // then
          status shouldEqual StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`
          val result: List[Map[String, String]] = toListOfMaps[String](responseAs[String]).get
          result.head("name") shouldBe "mainnet"
          result.head("displayName") shouldBe "Mainnet"
        }
      }

      "expose an endpoint to get the list of supported entities" in {
        // given
        platformDiscoveryOperations.addEntity(testNetworkPath, Entity("entity", "entity-name", 1))

        val overridesConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, None, Some(true))
                        )
                      )
                )
              )
        )

        // when
        Get("/v2/metadata/tezos/mainnet/entities") ~> addHeader("apiKey", "hooman") ~> sut(overridesConfiguration) ~> check {

          // then
          status shouldEqual StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`

          val parseResult = parser.parse(responseAs[String]).getOrElse(Json.arr())
          parseResult.isArray shouldBe true

          val headResult = parseResult.asArray.value.head.asObject.value
          headResult("name").value.asString.value shouldBe "entity"
          headResult("displayName").value.asString.value shouldBe "entity-name"
          headResult("count").value shouldBe Json.fromInt(1)
        }
      }

      "expose an endpoint to get the list of supported attributes" in {
        // given
        platformDiscoveryOperations.addEntity(testNetworkPath, Entity("entity", "entity-name", 1))
        platformDiscoveryOperations.addAttribute(
          testEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overridesConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(
                                None,
                                None,
                                Some(true),
                                None,
                                Map(
                                  "attribute" ->
                                      AttributeConfiguration(None, Some(true))
                                )
                              )
                        )
                      )
                )
              )
        )

        // when
        Get("/v2/metadata/tezos/mainnet/entity/attributes") ~> addHeader("apiKey", "hooman") ~> sut(
          overridesConfiguration
        ) ~> check {

          // then
          status shouldEqual StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`
          val result: List[Map[String, String]] = toListOfMaps[String](responseAs[String]).get
          result.head("name") shouldBe "attribute"
          result.head("displayName") shouldBe "attribute-name"
        }
      }

      "override additional data for attributes" in {
        // given
        platformDiscoveryOperations.addEntity(testNetworkPath, Entity("entity", "entity-name", 1))
        platformDiscoveryOperations.addAttribute(
          testEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overridesConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(
                                None,
                                None,
                                Some(true),
                                None,
                                Map(
                                  "attribute" ->
                                      AttributeConfiguration(
                                        displayName = None,
                                        visible = Some(true),
                                        description = Some("description"),
                                        placeholder = Some("placeholder"),
                                        scale = Some(6),
                                        dataType = Some("hash"),
                                        dataFormat = Some("dataFormat"),
                                        valueMap = Some(Map("0" -> "value")),
                                        reference = Some(Map("0" -> "value")),
                                        displayPriority = Some(1),
                                        displayOrder = Some(1),
                                        currencySymbol = Some("ꜩ"),
                                        currencySymbolCode = Some(42793)
                                      )
                                )
                              )
                        )
                      )
                )
              )
        )

        // when
        Get("/v2/metadata/tezos/mainnet/entity/attributes") ~> addHeader("apiKey", "hooman") ~> sut(
          overridesConfiguration
        ) ~> check {

          // then
          status shouldEqual StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`

          val parseResult = parser.parse(responseAs[String]).getOrElse(Json.arr())
          parseResult.isArray shouldBe true

          val headResult: JsonObject = parseResult.asArray.value.head.asObject.value
          headResult("name").value.asString.value shouldBe "attribute"
          headResult("displayName").value.asString.value shouldBe "attribute-name"
          headResult("description").value.asString.value shouldBe "description"
          headResult("placeholder").value.asString.value shouldBe "placeholder"
          headResult("dataFormat").value.asString.value shouldBe "dataFormat"
          headResult("scale").value shouldBe Json.fromInt(6)
          headResult("valueMap").value.asObject.value.toMap shouldBe Map("0" -> Json.fromString("value"))
          headResult("dataType").value.asString.value shouldBe "Hash"
          headResult("reference").value.asObject.value.toMap shouldBe Map("0" -> Json.fromString("value"))
          headResult("displayPriority").value shouldBe Json.fromInt(1)
          headResult("displayOrder").value shouldBe Json.fromInt(1)
          headResult("currencySymbol").value.asString.value shouldBe "ꜩ"
          headResult("currencySymbolCode").value shouldBe Json.fromInt(42793)
        }
      }

      "return 404 on getting attributes when parent entity is not enabled" in {
        // given
        platformDiscoveryOperations.addEntity(testNetworkPath, Entity("entity", "entity-name", 1))
        platformDiscoveryOperations.addAttribute(
          testEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overridesConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(None, Some(true), None)
                )
              )
        )

        // when
        Get("/v2/metadata/tezos/mainnet/entity/attributes") ~> addHeader("apiKey", "hooman") ~> sut(
          overridesConfiguration
        ) ~> check {

          // then
          status shouldEqual StatusCodes.NotFound
        }
      }

      "return 404 on getting attributes when parent network is not enabled" in {
        // given
        platformDiscoveryOperations.addEntity(testNetworkPath, Entity("entity", "entity-name", 1))
        platformDiscoveryOperations.addAttribute(
          testEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overridesConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(None, Some(true))
        )

        // when
        Get("/v2/metadata/tezos/mainnet/entity/attributes") ~> addHeader("apiKey", "hooman") ~> sut(
          overridesConfiguration
        ) ~> check {

          // then
          status shouldEqual StatusCodes.NotFound
        }
      }

      "return 404 on getting attributes when parent platform is not enabled" in {
        // given
        platformDiscoveryOperations.addEntity(testNetworkPath, Entity("entity", "entity-name", 1))
        platformDiscoveryOperations.addAttribute(
          testEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        // when
        Get("/v2/metadata/tezos/mainnet/entity/attributes") ~> addHeader("apiKey", "hooman") ~> sut(Map.empty) ~> check {

          // then
          status shouldEqual StatusCodes.NotFound
        }
      }

    }
}
