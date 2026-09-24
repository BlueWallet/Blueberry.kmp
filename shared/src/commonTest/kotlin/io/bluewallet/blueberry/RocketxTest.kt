package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.WalletSecretKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RocketxTest {
    @Test
    fun parseQuotes_reads_rate_amounts_and_venue() {
        val body =
            """
            {"data":[
              {"rateId":"r1","fromAmount":0.0134,"toAmount":0.0130267,
               "exchangeInfo":{"keyword":"CHANGELLY","id":"ex-1"},
               "fromTokenInfo":{"id":11},"toTokenInfo":{"id":22}},
              {"rateId":"r2","fromAmount":"0.014","toAmount":"0.0131",
               "exchangeInfo":{"keyword":"FIXEDFLOAT"},
               "fromTokenInfo":{"id":11},"toTokenInfo":{"id":22}}
            ]}
            """.trimIndent()
        val quotes = parseRocketxQuotes(body)
        assertEquals(2, quotes.size)
        assertEquals("r1", quotes[0].rateId)
        assertEquals("0.0134", quotes[0].fromAmount)
        assertEquals("0.0130267", quotes[0].toAmount)
        assertEquals("CHANGELLY", quotes[0].venue)
        assertEquals(11, quotes[0].fromTokenId)
        assertEquals(22, quotes[0].toTokenId)
        assertEquals("ex-1", quotes[0].exchangeId)
        assertEquals("r2", quotes[1].rateId)
        assertEquals("0.014", quotes[1].fromAmount)
    }

    @Test
    fun nextAfterQuotes_auto_swaps_single_and_lists_many() {
        val one =
            RocketxQuote(
                rateId = "r1",
                fromAmount = "0.01",
                toAmount = "0.009",
                venue = "A",
                fromTokenId = 1,
                toTokenId = 2,
                exchangeId = "ex-1",
            )
        assertEquals(RocketxQuoteAction.AutoSwap(one), nextAfterQuotes(listOf(one)))
        val two = one.copy(rateId = "r2", venue = "B")
        val many = nextAfterQuotes(listOf(one, two)) as RocketxQuoteAction.Choose
        assertEquals(2, many.quotes.size)
        assertEquals(RocketxQuoteAction.None, nextAfterQuotes(emptyList()))
    }

    @Test
    fun quotationUrl_uses_expectedToAmount_and_private_fixed_rate() {
        val url = rocketxQuotationUrl(expectedToAmountBtc = "0.0130267")
        assertTrue(url.startsWith("https://api.rocketx.exchange/v1/quotation?"))
        assertTrue(url.contains("expectedToAmount=0.0130267"))
        assertTrue(url.contains("fromNetwork=BTC"))
        assertTrue(url.contains("toNetwork=BTC"))
        assertTrue(url.contains("fixedRate=true"))
        assertTrue(url.contains("includedExchanges=PRIVATE_ONLY"))
    }

    @Test
    fun parseSwap_reads_nested_deposit_address() {
        val body =
            """{"requestId":"abc","swap":{"depositAddress":"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"}}"""
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            parseRocketxDepositAddress(body),
        )
        assertEquals("Bad Request", parseRocketxError("""{"err":"Bad Request","code":400}"""))
    }

    @Test
    fun parseSwap_reads_amounts_fee_and_eta() {
        val body =
            """
            {"requestId":"req-1","txId":99,
             "exchangeInfo":{"keyword":"CHANGELLY"},
             "estTimeInSeconds":{"avg":480},
             "swap":{"fromAmount":0.0134,"toAmount":0.0130267,
             "depositAddress":"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
             "partnerFee":1}}
            """.trimIndent()
        val swap = parseRocketxSwap(body)!!
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", swap.address)
        assertEquals("0.0134", swap.fromAmount)
        assertEquals("0.0130267", swap.toAmount)
        assertEquals("CHANGELLY", swap.venue)
        assertEquals("1", swap.partnerFee)
        assertEquals("req-1", swap.requestId)
        assertEquals("480", swap.estSeconds)
        assertTrue(formatRocketxSwap(swap).contains("pay 0.0134 BTC"))
        assertTrue(formatRocketxSwap(swap).contains("get 0.0130267 BTC"))
        assertTrue(formatRocketxSwap(swap).contains("service fee 1%"))
        assertTrue(formatRocketxSwap(swap).contains("about 8 min"))
    }

    @Test
    fun quote_covers_receive_and_scales_send_when_short() {
        val short =
            RocketxQuote(
                rateId = "r1",
                fromAmount = "0.0011",
                toAmount = "0.0009763",
                venue = "P",
                fromTokenId = 1,
                toTokenId = 2,
                exchangeId = "ex",
            )
        assertEquals(emptyList(), quotesCoveringReceive(listOf(short), expectedBtc = "0.0010123"))
        assertEquals("0.00114057", scaledSendAmount("0.0011", "0.0009763", "0.0010123"))
        val ok = short.copy(toAmount = "0.00104797")
        assertEquals(listOf(ok), quotesCoveringReceive(listOf(ok), expectedBtc = "0.0010123"))
        val longFrac = short.copy(toAmount = "0.00204875667568")
        assertEquals(listOf(longFrac), quotesCoveringReceive(listOf(longFrac), expectedBtc = "0.0020123"))
    }

    @Test
    fun quote_hunt_picks_closer_cover_and_midpoint_send() {
        val q = { from: String, to: String ->
            RocketxQuote("r", from, to, "P", 1, 2, "ex")
        }
        val expected = "0.0020123"
        var hunt = huntAfterQuotes(expected, listOf(q("0.0021", "0.0019773")))
        assertEquals(emptyList(), hunt.best)
        assertEquals("0.00213718", nextHuntSend(hunt))
        hunt = huntAfterSend(hunt, "0.00213718", listOf(q("0.00213718", "0.00204875")))
        assertEquals("0.00204875", hunt.best.single().toAmount)
        assertEquals("0.00211859", nextHuntSend(hunt))
        hunt = huntAfterSend(hunt, "0.00211859", listOf(q("0.00211859", "0.00202809")))
        assertEquals("0.00202809", hunt.best.single().toAmount)
        hunt = huntAfterSend(hunt, "0.002101", listOf(q("0.002101", "0.00201825")))
        assertEquals("0.00201825", hunt.best.single().toAmount)
    }

    @Test
    fun format_fee_percent_and_eta_minutes() {
        assertEquals("0.6%", formatRocketxPercent("0.5999999999999999"))
        assertEquals("about 4 min", formatRocketxEta("210"))
    }

    @Test
    fun swap_body_includes_exchange_id() {
        val quote =
            RocketxQuote(
                rateId = "r1",
                fromAmount = "0.01",
                toAmount = "0.009",
                venue = "A",
                fromTokenId = 1,
                toTokenId = 2,
                exchangeId = "ex-uuid",
            )
        val body = rocketxSwapBody(quote, destinationAddress = "bc1qdest", refundAddress = "bc1qref")
        assertTrue(body.contains("\"exchangeId\":\"ex-uuid\""))
        assertTrue(body.contains("\"rateId\":\"r1\""))
    }

    @Test
    fun expectedToAmount_from_exact_sats() {
        assertEquals("0.0130267", expectedToAmountBtc(SendAmount.Exact(1_302_670L), selectedSumSats = 0L))
        assertEquals("0.0001", expectedToAmountBtc(SendAmount.Exact(10_000L), selectedSumSats = 0L))
    }

    @Test
    fun quote_forwards_fetch_progress() {
        var percent = -1
        var stage = ""
        val covering =
            """{"data":[{"rateId":"r1","fromAmount":0.01,"toAmount":0.01,
              "exchangeInfo":{"keyword":"P","id":"ex"},
              "fromTokenInfo":{"id":1},"toTokenInfo":{"id":2}}]}"""
        val client =
            RocketxClient("k") { _, _, _, _, onProgress, _ ->
                onProgress?.onProgress(55, "Building circuit")
                200 to covering
            }
        runBlocking {
            client.quote("0.01") { p, s ->
                percent = p
                stage = s
            }
        }
        assertEquals(55, percent)
        assertEquals("Building circuit", stage)
    }

    @Test
    fun swap_covers_destination_when_get_meets_need() {
        assertEquals(true, swapCoversDestination("0.002", "0.002"))
        assertEquals(true, swapCoversDestination("0.00201", "0.002"))
        assertEquals(false, swapCoversDestination("0.00199", "0.002"))
        assertEquals(false, swapCoversDestination("", "0.002"))
    }

    @Test
    fun private_send_preview_names_the_provider() {
        assertEquals("Provider" to "rocketx.exchange", privateSendProvider())
    }

    @Test
    fun private_send_pay_is_exact_from_amount() {
        assertEquals(SendAmount.Exact(110_000L), privateSendPayAmount("0.0011"))
        assertEquals(null, privateSendPayAmount(""))
    }

    @Test
    fun max_builds_send_all_with_no_change() {
        assertEquals(SendAmount.Max, privateSendBuildAmount(SendAmount.Max, "0.0019"))
        assertEquals(SendAmount.Exact(110_000L), privateSendBuildAmount(SendAmount.Exact(10_000L), "0.0011"))
        assertEquals(null, privateSendBuildAmount(SendAmount.Exact(10_000L), ""))
        assertEquals("private send max left change", privateSendMaxSignError(changeSats = 1L, paidSats = 190_000L, fromBtc = "0.0019"))
        assertEquals("deposit below swap amount", privateSendMaxSignError(changeSats = 0L, paidSats = 180_000L, fromBtc = "0.0019"))
        assertNull(privateSendMaxSignError(changeSats = 0L, paidSats = 190_000L, fromBtc = "0.0019"))
    }

    @Test
    fun cancel_aborts_in_flight_rocketx_http() {
        var aborted = false
        val client =
            RocketxClient("k") { _, _, _, _, _, abort ->
                aborted = abort?.aborted == true
                error("should not fetch after cancel")
            }
        client.cancel()
        val result = runCatching { runBlocking { client.quote("0.01") } }
        assertTrue(aborted)
        assertTrue(result.isFailure)
    }

    @Test
    fun service_fee_is_deposit_minus_get_in_sats() {
        assertEquals(12_730L, rocketxServiceFeeSats("0.0011", "0.0009727"))
        assertEquals(1L, rocketxServiceFeeSats("0.0010000000001", "0.00099999"))
        assertEquals(null, rocketxServiceFeeSats("", "0.001"))
    }

    @Test
    fun overpay_sats_when_get_exceeds_requested() {
        assertEquals(1_000L, rocketxOverpaySats("0.00201", "0.002"))
        assertEquals(null, rocketxOverpaySats("0.002", "0.002"))
        assertEquals(null, rocketxOverpaySats("0.00199", "0.002"))
    }

    @Test
    fun destination_amount_from_api_to_amount() {
        assertEquals(1_302_670L, rocketxGetSats("0.0130267"))
        assertEquals(99_999L, rocketxGetSats("0.0009999900001"))
        assertEquals(null, rocketxGetSats(""))
    }

    @Test
    fun need_btc_is_exact_only() {
        assertEquals("0.002", privateSendNeedBtc(SendAmount.Exact(200_000L)))
        assertEquals(null, privateSendNeedBtc(SendAmount.Max))
    }

    @Test
    fun can_pay_deposit_plus_miner_fee() {
        assertEquals(true, privateSendCanPay("0.001", selectedSumSats = 120_000L, feeSats = 10_000L))
        assertEquals(false, privateSendCanPay("0.0012", selectedSumSats = 120_000L, feeSats = 10_000L))
        assertEquals(false, privateSendCanPay("", selectedSumSats = 120_000L, feeSats = 1L))
    }

    @Test
    fun max_from_is_selected_minus_miner_fee() {
        assertEquals("0.0019", privateSendMaxFromBtc(selectedSumSats = 200_000L, feeSats = 10_000L))
        assertEquals(null, privateSendMaxFromBtc(selectedSumSats = 500L, feeSats = 500L))
    }

    @Test
    fun max_swap_does_not_require_get_to_match_selected_sum() {
        val session =
            PrivateSendSession(
                destination = "bc1qdest",
                label = "lab",
                feeRateSatPerVb = 1.0,
                amountSats = SendAmount.Max,
                utxos = listOf(SendInputUtxo("aa", 0, 200_000L, ByteArray(0))),
                refundAddress = "bc1qref",
            )
        val swap =
            RocketxSwap(
                address = "bc1qdep",
                fromAmount = "0.0019",
                toAmount = "0.0017",
                venue = "P",
                partnerFee = "1",
                requestId = "r",
                estSeconds = "60",
            )
        assertNull(privateSendSwapError(session, swap))
    }

    @Test
    fun signing_error_for_watch_only() {
        assertEquals(null, privateSendSigningError(WalletSecretKind.MNEMONIC))
        assertEquals(null, privateSendSigningError(WalletSecretKind.WIF))
        assertEquals("Private send needs a signing wallet", privateSendSigningError(WalletSecretKind.ZPUB))
        assertEquals("Private send needs a signing wallet", privateSendSigningError(WalletSecretKind.ADDRESS))
    }

    @Test
    fun quote_for_send_uses_amount_param() {
        var url = ""
        val covering =
            """{"data":[{"rateId":"r1","fromAmount":0.0019,"toAmount":0.0017,
              "exchangeInfo":{"keyword":"P","id":"ex"},
              "fromTokenInfo":{"id":1},"toTokenInfo":{"id":2}}]}"""
        val client =
            RocketxClient("k") { _, u, _, _, _, _ ->
                url = u
                200 to covering
            }
        val quotes = runBlocking { client.quoteForSend("0.0019") }
        assertTrue(url.contains("amount=0.0019"))
        assertEquals("0.0019", quotes.single().fromAmount)
    }

    @Test
    fun persist_private_send_only_after_matching_broadcast() {
        assertEquals(true, shouldPersistPrivateSend("success", "aa", "aa"))
        assertEquals(false, shouldPersistPrivateSend("error", "aa", "aa"))
        assertEquals(false, shouldPersistPrivateSend("success", "bb", "aa"))
        val db = createSqliteDatabase(":memory:")
        val session =
            PrivateSendSession(
                destination = "bc1qdest",
                label = "lab",
                feeRateSatPerVb = 1.0,
                amountSats = SendAmount.Exact(50_000L),
                utxos = listOf(SendInputUtxo("bb".repeat(32), 1, 50_000L, ByteArray(0))),
                refundAddress = "bc1qref",
            )
        val swap =
            RocketxSwap(
                address = "bc1qdep",
                fromAmount = "0.0005",
                toAmount = "0.0004",
                venue = "P",
                partnerFee = "1",
                requestId = "ord-9",
                estSeconds = "60",
            )
        val signed =
            SignedSendResult(
                txHex = "010203",
                txid = "aa".repeat(32),
                feeSats = 200L,
                vsize = 140,
                changeSats = 0L,
                changeVouts = emptyList(),
            )
        val row = privateSendRecord(session, swap, signed)
        persistPrivateSendIfBroadcast(db, row, BroadcastSnapshot(txHex = "010203", phase = "sending"))
        assertEquals(null, db.privateSends.get(signed.txid))
        persistPrivateSendIfBroadcast(db, row, BroadcastSnapshot(txHex = "010203", phase = "success"))
        val saved = db.privateSends.get(signed.txid)!!
        assertEquals(PRIVATE_SEND_PARTNER_ROCKETX, saved.partner)
        assertEquals("ord-9", saved.orderId)
        assertEquals("010203", saved.txHex)
        assertEquals("bc1qdest", saved.destination)
        assertEquals("bc1qref", saved.refundAddress)
        assertEquals(1, saved.coins.size)
        assertEquals("bb".repeat(32), saved.coins[0].txid)
        assertEquals(1, saved.coins[0].vout)
        assertEquals(50_000L, saved.coins[0].valueSats)
        assertEquals(0L, saved.confirmedInBlock)
        assertTrue(saved.createdAt > 0L)
        db.close()
    }
}
