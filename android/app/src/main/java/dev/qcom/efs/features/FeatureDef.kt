package dev.qcom.efs.features

/**
 * One write into an EFS item file. [bytes] is the exact payload as decimal
 * byte values (port of mtbtool's NvWrite, which stores them as a decimal
 * string).
 */
data class NvWrite(val path: String, val bytes: List<Int>)

/**
 * A modem capability that can be turned off by writing fixed bytes into EFS
 * item files. [reads] lists the item files whose contents determine whether
 * the feature is already disabled; [isDisabled] receives one byte array per
 * EXISTING read path (all present) and must answer for exactly those.
 *
 * 1:1 port of mtbtool's FeatureDef table (dev.henrik.mtbtool.FeatureDef).
 */
data class FeatureDef(
    val id: String,
    val label: String,
    val reads: List<String>,
    val writes: List<NvWrite>,
    val isDisabled: (List<List<Int>>) -> Boolean,
)

/** SIM1 item files carry this filename suffix (SIM0 has none). */
const val SUBSCRIPTION_SUFFIX = "_Subscription01"

/**
 * Maps a canonical (SIM0) item path to the selected SIM slot. SIM1 appends
 * [SUBSCRIPTION_SUFFIX] to the filename, idempotently.
 */
fun slotPath(path: String, slot: Int): String {
    if (slot == 0) return path
    if (path.endsWith(SUBSCRIPTION_SUFFIX)) return path
    return path + SUBSCRIPTION_SUFFIX
}

private val NR_BASE = "/nv/item_files/modem/nr5g/RRC/"

/**
 * The 12 NR5G capability toggles from mtbtool's features screen. Bytes and
 * disabled-detection are byte-for-byte faithful to the mtbtool source.
 */
val ALL_FEATURES: List<FeatureDef> = listOf(
    FeatureDef(
        id = "r17_2t2t",
        label = "Disable R17 2T2T UL Tx Switching",
        reads = listOf(NR_BASE + "cap_control_nrca_xf_plus_yt_swul_r17_band_combos_v2"),
        writes = listOf(NvWrite(NR_BASE + "cap_control_nrca_xf_plus_yt_swul_r17_band_combos_v2", List(24) { 0 })),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.all { it == 0 }
        },
    ),
    FeatureDef(
        id = "r16_2t1t",
        label = "Disable R16 2T1T UL Tx Switching",
        reads = listOf(
            NR_BASE + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2",
            NR_BASE + "cap_swul_type_control",
            NR_BASE + "cap_swul_control",
            NR_BASE + "cap_swul_3x_control",
            NR_BASE + "cap_swul_4x_control",
            NR_BASE + "cap_swul_5x_control",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2", List(18) { 0 }),
            NvWrite(NR_BASE + "cap_swul_type_control", listOf(0, 0, 0)),
            NvWrite(NR_BASE + "cap_swul_control", listOf(0)),
            NvWrite(NR_BASE + "cap_swul_3x_control", listOf(0)),
            NvWrite(NR_BASE + "cap_swul_4x_control", listOf(0)),
            NvWrite(NR_BASE + "cap_swul_5x_control", listOf(0)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 6) return@FeatureDef false
            byteArrays.all { bytes -> bytes.all { it == 0 } }
        },
    ),
    FeatureDef(
        id = "ul_mimo",
        label = "Disable UL MIMO",
        reads = listOf(NR_BASE + "cap_limit_rf_mimo"),
        writes = listOf(NvWrite(NR_BASE + "cap_limit_rf_mimo", listOf(1, 1) + List(42) { 0 })),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 3 && b[0] == 1 && b[1] == 1 && b[2] == 0
        },
    ),
    FeatureDef(
        id = "fdd_ul_mimo",
        label = "Disable FDD-only UL MIMO",
        reads = listOf(NR_BASE + "cap_control_fdd_ul_mimo"),
        writes = listOf(NvWrite(NR_BASE + "cap_control_fdd_ul_mimo", listOf(0, 0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 2 && b[0] == 0 && b[1] == 0
        },
    ),
    FeatureDef(
        id = "nr_ulca",
        label = "Disable NR UL-CA",
        reads = listOf(
            NR_BASE + "cap_control_nrca_2x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_3x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos_v2",
            NR_BASE + "cap_control_nrca_f_plus_f_ulca_band_combos",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_nrca_2x_f_plus_t_band_combos", listOf(0)),
            NvWrite(NR_BASE + "cap_control_nrca_3x_f_plus_t_band_combos", listOf(1, 1, 1, 1, 0, 0)),
            NvWrite(NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos", listOf(0, 1, 1)),
            NvWrite(NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos_v2", listOf(0, 1, 1, 0, 1, 1)),
            NvWrite(NR_BASE + "cap_control_nrca_f_plus_f_ulca_band_combos", listOf(0, 0)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 5) return@FeatureDef false
            val (b0, b1, b2, b3, b4) = byteArrays
            b0.firstOrNull() == 0 &&
                b1.size >= 6 && b1[0] == 1 && b1[1] == 1 && b1[2] == 1 && b1[3] == 1 && b1[4] == 0 && b1[5] == 0 &&
                b2.size >= 3 && b2[0] == 0 && b2[1] == 1 && b2[2] == 1 &&
                b3.size >= 6 && b3[0] == 0 && b3[1] == 1 && b3[2] == 1 && b3[3] == 0 && b3[4] == 1 && b3[5] == 1 &&
                b4.size >= 2 && b4[0] == 0 && b4[1] == 0
        },
    ),
    FeatureDef(
        id = "dl_nrca",
        label = "Disable NR DL-CA",
        reads = listOf(NR_BASE + "cap_nrca_downgrade_1cc"),
        writes = listOf(NvWrite(NR_BASE + "cap_nrca_downgrade_1cc", listOf(1))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 1
        },
    ),
    FeatureDef(
        id = "lowband_4rx",
        label = "Disable Lowbands 4Rx",
        reads = listOf(NR_BASE + "cap_limit_rf_mimo"),
        writes = listOf(
            NvWrite(
                NR_BASE + "cap_limit_rf_mimo",
                listOf(0, 0, 0, 5) +
                    listOf(8, 0, 0, 2, 20, 0, 0, 2, 26, 0, 0, 2, 28, 0, 0, 2, 71, 0, 0, 2) +
                    List(20) { 0 },
            ),
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            val bandBytes = listOf(8, 0, 0, 2, 20, 0, 0, 2, 26, 0, 0, 2, 28, 0, 0, 2, 71, 0, 0, 2)
            b.size >= 24 &&
                b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 5 &&
                bandBytes.indices.all { i -> b[4 + i] == bandBytes[i] }
        },
    ),
    FeatureDef(
        id = "nsa_tf_nrca",
        label = "Disable T+F NSA NR-CA",
        reads = listOf(
            NR_BASE + "cap_control_mrdc_f_plus_t_band_combos",
            NR_BASE + "cap_control_t_plus_f_band_combos",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_mrdc_f_plus_t_band_combos", listOf(0)),
            NvWrite(NR_BASE + "cap_control_t_plus_f_band_combos", listOf(7)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 2) return@FeatureDef false
            val (b0, b1) = byteArrays
            b0.firstOrNull() == 0 &&
                b1.firstOrNull() == 7
        },
    ),
    FeatureDef(
        id = "nsa_ff_nrca",
        label = "Disable F+F NSA NR-CA",
        reads = listOf(NR_BASE + "cap_control_mrdc_2x_f_plus_f_band_combos"),
        writes = listOf(NvWrite(NR_BASE + "cap_control_mrdc_2x_f_plus_f_band_combos", listOf(0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 0
        },
    ),
    FeatureDef(
        id = "nsa_tt_nrca",
        label = "Disable T+T NSA NR-CA",
        reads = listOf(
            NR_BASE + "cap_control_mrdc_t_plus_t_band_combos",
            NR_BASE + "cap_control_nr_t_plus_t_band_combos",
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_mrdc_t_plus_t_band_combos", listOf(0, 0, 0)),
            NvWrite(NR_BASE + "cap_control_nr_t_plus_t_band_combos", listOf(0, 0)),
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 2) return@FeatureDef false
            val (b0, b1) = byteArrays
            b0.size >= 3 && b0[0] == 0 && b0[1] == 0 && b0[2] == 0 &&
                b1.size >= 2 && b1[0] == 0 && b1[1] == 0
        },
    ),
    FeatureDef(
        id = "segmentation",
        label = "Disable Segmentation",
        reads = listOf(NR_BASE + "cap_msg_segmentation"),
        writes = listOf(NvWrite(NR_BASE + "cap_msg_segmentation", listOf(0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 0
        },
    ),
    FeatureDef(
        id = "dss",
        label = "Disable DSS",
        reads = listOf(NR_BASE + "cap_dss_control"),
        writes = listOf(NvWrite(NR_BASE + "cap_dss_control", listOf(0, 0))),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 2 && b[0] == 0 && b[1] == 0
        },
    ),
)
