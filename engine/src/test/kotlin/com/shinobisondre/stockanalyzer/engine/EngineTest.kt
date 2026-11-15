package com.shinobisondre.stockanalyzer.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class EngineTest {
    @Test
    fun engineSelfTest() {
        val json = javaClass.classLoader.getResource("ti_reference.json")!!.readText()
        val failures = EngineSelfTest.runAll(json)
        assertTrue(failures.isEmpty(), "Engine self-test failures:\n" + failures.joinToString("\n"))
    }
}
