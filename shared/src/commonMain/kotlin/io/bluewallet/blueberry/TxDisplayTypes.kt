package io.bluewallet.blueberry

/** Local calendar time. The display rules compare dates, not raw epochs. */
data class TxCivilTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
)

enum class TxDirection { In, Out, Moved }

enum class TxOutcome { None, Replaced, Dropped }

enum class TxBroadcast { Tor, Direct, Unknown }

enum class TxTone { Green, Blue, Amber, Neutral }

enum class TxStripCell { Block, Confirmed, Empty, Pending }

enum class TxGlyph { Received, Sent, Moved, Failed }

enum class TxBadge { None, Warning, Frozen }

data class TxDisplayBlock(
    val height: Int,
    val hash: String,
    val mined: TxCivilTime,
    val index: Int,
    val txCount: Int?,
    val feeRateLabel: String? = null,
)

data class TxDisplayOutput(
    val vout: Int,
    val valueSat: Long,
    val mine: Boolean,
    val change: Boolean = false,
    val address: String? = null,
    val addressIndex: Int? = null,
    val addressUseCount: Int = 1,
    val label: String = "",
    val frozen: Boolean = false,
    val spent: Boolean = false,
    val spentTxid: String? = null,
    val spentLabel: String? = null,
    val groupName: String? = null,
    val groupSize: Int = 1,
    /** Cost to spend this output, when a fee rate is known. Dust uses it. */
    val spendCostSat: Long? = null,
)

data class TxDisplayInputCoin(
    val outpoint: String,
    val valueSat: Long,
    val label: String? = null,
    val groupName: String? = null,
    val groupSize: Int = 1,
    val colorArgb: Long? = null,
)

data class TxDisplayInput(
    val txid: String,
    val direction: TxDirection,
    val label: String = "",
    val inputCount: Int,
    val walletInputs: List<TxDisplayInputCoin> = emptyList(),
    val outputs: List<TxDisplayOutput>,
    val baseBytes: Int,
    val witnessBytes: Int,
    val block: TxDisplayBlock? = null,
    val broadcastAt: TxCivilTime? = null,
    val broadcastEpochS: Long? = null,
    val now: TxCivilTime,
    val nowEpochS: Long,
    val tipHeight: Int? = null,
    val broadcast: TxBroadcast = TxBroadcast.Unknown,
    val outcome: TxOutcome = TxOutcome.None,
    val replacementTxid: String? = null,
    val ownSend: Boolean = false,
)

data class TxAmountParts(
    val sign: String,
    val significant: String,
    val trailingZeros: String,
)

data class TxHeader(
    val title: String,
    val subtitle: String,
)

data class TxHero(
    val amount: TxAmountParts,
    val sub: String,
    val toLabel: String? = null,
    val toAddress: String? = null,
    val toMore: Int = 0,
)

data class TxStatus(
    val title: String,
    val sub: String,
    val badge: String?,
    val badgeTone: TxTone,
    val cells: List<TxStripCell>,
    val left: String,
    val right: String?,
    val note: String,
    val speedUp: Boolean,
    val failed: Boolean = false,
    val replacementTxid: String? = null,
)

data class TxLabelCard(
    val title: String,
    val subtitle: String,
    val placeholder: String,
    val hasLabel: Boolean,
)

data class TxFlowChipModel(
    val kind: String,
    val amount: Long? = null,
    val title: String,
    val sub: String? = null,
    val colorArgb: Long? = null,
)

data class TxFlowView(
    val count: String,
    val fromColumn: String,
    val toColumn: String,
    val inputs: List<TxFlowChipModel>,
    val outputs: List<TxFlowChipModel>,
    val note: String?,
)

data class TxCoinCard(
    val outpoint: String,
    val title: String,
    val sub: String,
    val subTone: TxTone,
    val amountSats: Long,
    val address: String?,
    val addressNote: String,
    val frozen: Boolean,
    val spent: Boolean,
    val spentTxid: String?,
    val actions: List<String>,
)

data class TxReveal(
    val tone: TxTone,
    val title: String,
    val sub: String,
    val action: String? = null,
    val outpoint: String? = null,
)

data class TxPosition(
    val cell: Int,
    val txPerCell: Int,
    val label: String,
    val index: Int,
    val txCount: Int,
)

data class TxDetailsModel(
    val transactionSummary: String,
    val blockSummary: String,
    val txid: String,
    val rawSizeBytes: Int,
    val weightWU: Int,
    val vsizeVB: Int,
    val baseBytes: Int,
    val witnessBytes: Int,
    val inputCount: Int,
    val outputCount: Int,
    val position: TxPosition?,
    val blockHash: String?,
    val blockHashLeadingZeros: Int?,
    val pendingBlock: Boolean,
    val heightLabel: String?,
    val feeRateLabel: String?,
)

data class TxDetail(
    val header: TxHeader,
    val hero: TxHero,
    val status: TxStatus,
    val label: TxLabelCard,
    val flow: TxFlowView,
    val coins: List<TxCoinCard>,
    val reveals: List<TxReveal>,
    val details: TxDetailsModel,
    val direction: TxDirection,
    val outcome: TxOutcome,
    val amountSats: Long,
)

enum class TxListLeadTone { Secondary, Amber, Frozen }

sealed interface TxListSubtitle {
    data class Status(
        val text: String,
    ) : TxListSubtitle

    data class Strip(
        val pending: Boolean,
        val confirmations: Int,
        val label: String,
    ) : TxListSubtitle

    data class Noted(
        val lead: String,
        val tone: TxListLeadTone,
        val date: String,
    ) : TxListSubtitle

    data class DateOnly(
        val date: String,
    ) : TxListSubtitle
}

data class TxListRow(
    val glyph: TxGlyph,
    val pendingGlyph: Boolean,
    val badge: TxBadge,
    val title: String,
    val titleMuted: Boolean,
    val subtitle: TxListSubtitle,
    val amount: TxAmountParts,
    val struck: Boolean,
    val amountSub: String?,
)

data class TxScreen(
    val detail: TxDetail,
    val list: TxListRow,
)

data class TxListItem(
    val txid: String,
    val pending: Boolean,
    val whenCivil: TxCivilTime?,
    val epochS: Long,
    val row: TxListRow,
)

data class TxListSection(
    val title: String,
    val items: List<TxListItem>,
)
