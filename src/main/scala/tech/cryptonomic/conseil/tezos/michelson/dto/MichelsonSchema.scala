package tech.cryptonomic.conseil.tezos.michelson.dto

/* Class representing a whole Michelson schema */
case class MichelsonSchema(parameter: MichelsonExpression, storage: MichelsonExpression, code: MichelsonCode) extends MichelsonElement