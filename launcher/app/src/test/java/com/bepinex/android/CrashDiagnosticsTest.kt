package com.bepinex.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pending-launch record's modpack list is the export's only source for
 * "which packs did this run use" (ADR-0022), so its storage encoding must
 * preserve launch order exactly, and the three states it can be in —
 * not recorded (old record), vanilla (recorded empty), and a real list —
 * must stay distinguishable in the report.
 */
class CrashDiagnosticsTest {

    @Test
    fun `modpack names round trip in launch order`() {
        val names = listOf("TowerBanLogger", "TowerWave", "TowerAccountTools", "TheTowerMod")
        assertEquals(names, CrashDiagnostics.decodeModpackNames(CrashDiagnostics.encodeModpackNames(names)))
    }

    @Test
    fun `a single modpack round trips`() {
        val names = listOf("TheTowerMod")
        assertEquals(names, CrashDiagnostics.decodeModpackNames(CrashDiagnostics.encodeModpackNames(names)))
    }

    @Test
    fun `vanilla encodes to empty and decodes back to empty`() {
        assertEquals("", CrashDiagnostics.encodeModpackNames(emptyList()))
        assertEquals(emptyList<String>(), CrashDiagnostics.decodeModpackNames(""))
        assertEquals(emptyList<String>(), CrashDiagnostics.decodeModpackNames(null))
    }

    @Test
    fun `report line distinguishes not recorded, vanilla, and a real list`() {
        assertEquals("(not recorded)", CrashDiagnostics.formatModpackNames(null))
        assertEquals("(vanilla)", CrashDiagnostics.formatModpackNames(emptyList()))
        assertEquals(
            "TowerBanLogger, TowerWave",
            CrashDiagnostics.formatModpackNames(listOf("TowerBanLogger", "TowerWave"))
        )
    }
}
